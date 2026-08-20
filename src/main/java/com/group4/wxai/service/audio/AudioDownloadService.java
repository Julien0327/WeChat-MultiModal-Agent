package com.group4.wxai.service.audio;

import com.group4.wxai.config.AudioDownloadProperties;
import com.group4.wxai.model.SynthesizedAudio;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class AudioDownloadService {

    private static final Pattern SAFE_TOKEN = Pattern.compile("^[a-f0-9]{32}$");
    private static final int MAX_AUDIO_BYTES = 20 * 1024 * 1024;
    private static final String DOWNLOAD_PATH = "/wx/audio/";

    private final AudioDownloadProperties properties;
    private final Map<String, StoredAudio> storedAudio = new ConcurrentHashMap<>();

    public Optional<String> publishMp3(SynthesizedAudio audio) {
        if (!properties.isConfigured()) {
            log.warn("未配置 PUBLIC_BASE_URL，无法生成 MP3 下载链接");
            return Optional.empty();
        }
        if (audio == null || audio.isEmpty() || !isMp3(audio.format())) {
            log.warn("合成音频不是有效的 MP3，无法生成下载链接");
            return Optional.empty();
        }
        if (audio.data().length > MAX_AUDIO_BYTES) {
            log.warn("合成音频超过 MP3 下载大小限制: size={}", audio.data().length);
            return Optional.empty();
        }

        Instant now = Instant.now();
        removeExpired(now);
        String token = UUID.randomUUID().toString().replace("-", "");
        storedAudio.put(token, new StoredAudio(audio.data().clone(), now.plus(properties.effectiveTtl())));

        String baseUrl = properties.getPublicBaseUrl().trim().replaceFirst("/+$", "");
        return Optional.of(baseUrl + DOWNLOAD_PATH + token + ".mp3");
    }

    public Optional<byte[]> findMp3(String token) {
        if (token == null || !SAFE_TOKEN.matcher(token).matches()) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        StoredAudio audio = storedAudio.get(token);
        if (audio == null) {
            return Optional.empty();
        }
        if (!audio.expiresAt().isAfter(now)) {
            storedAudio.remove(token, audio);
            return Optional.empty();
        }
        return Optional.of(audio.data().clone());
    }

    private boolean isMp3(String format) {
        if (format == null) {
            return false;
        }
        String normalized = format.trim().toLowerCase(Locale.ROOT);
        return "mp3".equals(normalized) || "mpeg".equals(normalized);
    }

    private void removeExpired(Instant now) {
        storedAudio.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    private record StoredAudio(byte[] data, Instant expiresAt) {
    }
}
