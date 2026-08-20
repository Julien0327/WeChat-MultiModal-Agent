package com.group4.wxai.service.ilink;

import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.group4.wxai.service.TextProcessingService;
import com.group4.wxai.service.wx.WxMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ILinkInboundMessageService {

    private static final int MAX_DEDUPLICATION_IDS = 5000;
    private final TextProcessingService textProcessingService;
    private final ILinkMediaProcessingService mediaProcessingService;
    private final WxMessageService messageService;
    private final Set<Long> processedMessageIds = ConcurrentHashMap.newKeySet();

    @EventListener
    public void onMessages(ILinkMessagesReceived event) {
        for (WeixinMessage message : event.messages()) {
            handleMessage(message);
        }
    }

    private void handleMessage(WeixinMessage message) {
        if (message == null || message.getFrom_user_id() == null || message.getFrom_user_id().isBlank()) {
            return;
        }
        Long messageId = message.getMessage_id();
        if (messageId != null && !processedMessageIds.add(messageId)) {
            log.debug("忽略重复 iLink 消息: id={}", messageId);
            return;
        }
        if (processedMessageIds.size() > MAX_DEDUPLICATION_IDS) {
            processedMessageIds.clear();
            if (messageId != null) {
                processedMessageIds.add(messageId);
            }
        }

        String userId = message.getFrom_user_id();
        if (message.getItem_list() == null || message.getItem_list().isEmpty()) {
            return;
        }
        for (MessageItem item : message.getItem_list()) {
            if (item == null) {
                continue;
            }
            if (item.getText_item() != null && item.getText_item().getText() != null
                    && !item.getText_item().getText().isBlank()) {
                textProcessingService.processAsync(userId, item.getText_item().getText().trim());
            } else if (item.getImage_item() != null) {
                messageService.sendTextMessage(userId, "我先看一下这张图，稍等。");
                mediaProcessingService.processImageAsync(userId, item);
            } else if (item.getVoice_item() != null) {
                mediaProcessingService.processVoiceAsync(userId, item);
            } else if (item.getFile_item() != null) {
                messageService.sendTextMessage(userId, "文件已经收到，但当前还没有接入通用文件解析。");
            } else if (item.getVideo_item() != null) {
                messageService.sendTextMessage(userId, "视频已经收到，但当前还不能分析视频内容。");
            }
        }
    }
}
