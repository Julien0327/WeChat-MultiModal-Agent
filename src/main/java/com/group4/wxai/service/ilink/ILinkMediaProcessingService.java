package com.group4.wxai.service.ilink;

import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.VoiceItem;
import com.group4.wxai.model.ReplyMode;
import com.group4.wxai.service.TextProcessingService;
import com.group4.wxai.service.llm.LlmService;
import com.group4.wxai.service.wx.WxMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ILinkMediaProcessingService {

    private final ILinkClientManager clientManager;
    private final LlmService llmService;
    private final TextProcessingService textProcessingService;
    private final WxMessageService messageService;

    @Async("llmAsyncExecutor")
    public void processImageAsync(String userId, MessageItem item) {
        try {
            byte[] imageData = clientManager.downloadMedia(item);
            String description = llmService.chatWithImageData(
                    userId,
                    "直接用自然的中文聊天文本说说你在图里看到了什么。不要使用模板开场、角色标签或占位符。",
                    imageData,
                    detectImageMimeType(imageData)
            );
            messageService.sendTextMessage(userId, description);
        } catch (Exception e) {
            log.error("处理 iLink 图片失败: user={}", userId, e);
            messageService.sendTextMessage(userId, "这张图刚才没看成功，等会儿再试一下。");
        }
    }

    @Async("llmAsyncExecutor")
    public void processVoiceAsync(String userId, MessageItem item) {
        try {
            VoiceItem voice = item.getVoice_item();
            String transcript = voice == null ? "" : clean(voice.getText());
            if (transcript.isBlank()) {
                byte[] audioData = clientManager.downloadMedia(item);
                transcript = llmService.speechToText(audioData, inferAudioFormat(voice));
            }
            textProcessingService.process(userId, transcript, ReplyMode.TEXT);
        } catch (Exception e) {
            log.error("处理 iLink 语音失败: user={}", userId, e);
            messageService.sendTextMessage(userId, "抱歉，这段语音刚才没有识别成功，请再说一次或改用文字。");
        }
    }

    private String inferAudioFormat(VoiceItem voice) {
        return voice != null && Integer.valueOf(6).equals(voice.getEncode_type()) ? "silk" : "wav";
    }

    private String detectImageMimeType(byte[] data) {
        if (data != null && data.length >= 8
                && (data[0] & 0xff) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') {
            return "image/png";
        }
        if (data != null && data.length >= 6
                && data[0] == 'G' && data[1] == 'I' && data[2] == 'F') {
            return "image/gif";
        }
        return "image/jpeg";
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
