package com.group4.wxai.service.ilink;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.ILinkClientBuilder;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.group4.wxai.config.ILinkProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class ILinkClientManager {

    private final ILinkProperties properties;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final AtomicReference<ILinkClient> clientRef = new AtomicReference<>();
    private final AtomicBoolean polling = new AtomicBoolean(false);
    private final ExecutorService pollingExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ilink-message-poll");
        thread.setDaemon(true);
        return thread;
    });

    @PostConstruct
    public void initialize() {
        if (!properties.isEnabled()) {
            log.info("微信 iLink 通道已禁用");
            return;
        }
        LoginContext restored = loadSession();
        ILinkClient client = createClient(restored);
        clientRef.set(client);
        if (restored != null) {
            log.info("已从本地会话恢复微信 iLink 登录: bot={}", restored.getBotId());
            startPolling(client);
        } else {
            log.info("微信 iLink 尚未登录，请调用受保护的 /ilink/admin/login 接口获取扫码内容");
        }
    }

    public synchronized String startLogin() {
        return startLogin(false);
    }

    public synchronized String startLogin(boolean force) {
        ensureEnabled();
        ILinkClient current = clientRef.get();
        if (current != null && current.isLoggedIn() && !force) {
            throw new IllegalStateException("微信 iLink 已登录，无需重复扫码");
        }
        if (current != null) {
            current.close();
        }
        if (force) {
            deleteStoredSession();
        }
        ILinkClient fresh = createClient(null);
        clientRef.set(fresh);
        return fresh.executeLogin();
    }

    public ILinkStatus status() {
        ILinkClient client = clientRef.get();
        LoginContext context = client == null ? null : client.getLoginContext();
        return new ILinkStatus(
                properties.isEnabled(),
                client != null && client.isLoggedIn(),
                client == null ? "NOT_INITIALIZED" : client.getConnectionStatus().name(),
                context == null ? "" : context.getBotId()
        );
    }

    public boolean isILinkUser(String userId) {
        return properties.isEnabled() && userId != null && userId.endsWith("@im.wechat");
    }

    public void sendText(String userId, String content) throws IOException {
        requireLoggedInClient().sendText(userId, content);
    }

    public void sendImage(String userId, byte[] data, String fileName, String caption) throws IOException {
        requireLoggedInClient().sendImage(userId, data, fileName, caption);
    }

    public void sendFile(String userId, byte[] data, String fileName, String caption) throws IOException {
        requireLoggedInClient().sendFile(userId, data, fileName, caption);
    }

    public byte[] downloadMedia(MessageItem item) throws IOException {
        return requireLoggedInClient().downloadMediaFromMessageItem(item);
    }

    public void requireAdminToken(String providedToken) {
        String expected = properties.getAdminToken();
        if (expected == null || expected.isBlank()) {
            throw new IllegalStateException("请先配置 ILINK_ADMIN_TOKEN，再使用 iLink 管理接口");
        }
        byte[] expectedBytes = expected.trim().getBytes(StandardCharsets.UTF_8);
        byte[] providedBytes = providedToken == null
                ? new byte[0] : providedToken.trim().getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedBytes, providedBytes)) {
            throw new SecurityException("iLink 管理 Token 不正确");
        }
    }

    private ILinkClient createClient(LoginContext restored) {
        ILinkConfig config = ILinkConfig.builder()
                .heartbeatEnabled(false)
                .loginTimeoutMs(properties.effectiveLoginTimeout().toMillis())
                .channelVersion(properties.getChannelVersion())
                .build();
        ILinkClientBuilder builder = ILinkClient.builder()
                .config(config)
                .onMessage(messages -> eventPublisher.publishEvent(new ILinkMessagesReceived(messages)))
                .onLogin(new OnLoginListener() {
                    @Override
                    public void onLoginSuccess(LoginContext context) {
                        persistSession(context);
                        ILinkClient active = clientRef.get();
                        if (active != null) {
                            startPolling(active);
                        }
                        log.info("微信 iLink 扫码登录成功: bot={}", context.getBotId());
                    }

                    @Override
                    public void onLoginFailure(Throwable throwable) {
                        log.error("微信 iLink 登录失败", throwable);
                    }
                });
        if (restored != null) {
            builder.loginContext(restored);
        }
        return builder.build();
    }

    private void startPolling(ILinkClient client) {
        if (!polling.compareAndSet(false, true)) {
            return;
        }
        pollingExecutor.submit(() -> pollLoop(client));
    }

    private void pollLoop(ILinkClient client) {
        try {
            while (!Thread.currentThread().isInterrupted()
                    && clientRef.get() == client && client.isLoggedIn()) {
                try {
                    var messages = client.getUpdates();
                    if (messages == null || messages.isEmpty()) {
                        Thread.sleep(100);
                    }
                } catch (IOException e) {
                    log.warn("微信 iLink 拉取消息失败，将重试: {}", e.getMessage());
                    sleep(properties.effectivePollErrorBackoff());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            polling.set(false);
            ILinkClient next = clientRef.get();
            if (next != null && next != client && next.isLoggedIn()) {
                startPolling(next);
            }
        }
    }

    private void sleep(Duration duration) throws InterruptedException {
        Thread.sleep(duration.toMillis());
    }

    private ILinkClient requireLoggedInClient() {
        ILinkClient client = clientRef.get();
        if (client == null || !client.isLoggedIn()) {
            throw new IllegalStateException("微信 iLink 尚未登录，请先扫码");
        }
        return client;
    }

    private void ensureEnabled() {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("微信 iLink 通道未启用");
        }
    }

    private LoginContext loadSession() {
        Path path = sessionPath();
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            StoredLogin stored = objectMapper.readValue(path.toFile(), StoredLogin.class);
            if (stored.botToken() == null || stored.botToken().isBlank()
                    || stored.botId() == null || stored.botId().isBlank()) {
                return null;
            }
            return new LoginContext(stored.botToken(), stored.userId(), stored.botId(), stored.baseUrl());
        } catch (Exception e) {
            log.error("读取微信 iLink 会话失败，将等待重新扫码: file={}", path.toAbsolutePath(), e);
            return null;
        }
    }

    private void persistSession(LoginContext context) {
        Path path = sessionPath();
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            objectMapper.writeValue(path.toFile(), new StoredLogin(
                    context.getBotToken(), context.getUserId(), context.getBotId(), context.getBaseUrl()
            ));
            try {
                Files.setPosixFilePermissions(path, Set.of(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE
                ));
            } catch (IOException | UnsupportedOperationException ignored) {
                // Windows does not expose POSIX permissions.
            }
        } catch (Exception e) {
            log.error("保存微信 iLink 会话失败: file={}", path.toAbsolutePath(), e);
        }
    }

    private void deleteStoredSession() {
        Path path = sessionPath();
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new IllegalStateException("无法清理旧的微信 iLink 会话文件: " + path, e);
        }
    }

    private Path sessionPath() {
        return Paths.get(properties.getSessionFile()).toAbsolutePath().normalize();
    }

    @PreDestroy
    public void close() {
        pollingExecutor.shutdownNow();
        ILinkClient client = clientRef.getAndSet(null);
        if (client != null) {
            client.close();
        }
    }

    public record ILinkStatus(boolean enabled, boolean loggedIn, String connectionStatus, String botId) {
    }

    private record StoredLogin(String botToken, String userId, String botId, String baseUrl) {
    }
}
