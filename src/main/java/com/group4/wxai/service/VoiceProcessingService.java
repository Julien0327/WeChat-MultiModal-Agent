package com.group4.wxai.service;

import com.group4.wxai.model.ReplyMode;
import com.group4.wxai.service.llm.LlmService;
import com.group4.wxai.service.wx.WxMediaService;
import com.group4.wxai.service.wx.WxMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class VoiceProcessingService {

    private final LlmService llmService;
    private final WxMediaService wxMediaService;
    private final WxMessageService wxMessageService;
    private final TextProcessingService textProcessingService;

    @Async("llmAsyncExecutor")
    public void processAsync(String userOpenId, String recognition, String mediaId, String format) {
        try {
            String text = resolveTranscript(userOpenId, recognition, mediaId, format);
            textProcessingService.process(userOpenId, text, ReplyMode.TEXT);
        } catch (Exception e) {
            log.error("异步处理语音消息失败: user={}, format={}", userOpenId, format, e);
            wxMessageService.sendTextMessage(userOpenId, "抱歉，语音识别或处理失败，请稍后重试。");
        }
    }

    private String resolveTranscript(String userOpenId, String recognition, String mediaId, String format)
            throws Exception {
        try {
            log.info("使用程序内ASR识别微信语音: user={}, format={}", userOpenId, format);
            byte[] audioData = wxMediaService.downloadTemporaryMedia(mediaId);
            return llmService.speechToText(audioData, format);
        } catch (Exception asrException) {
            if (recognition != null && !recognition.isBlank()) {
                log.warn("程序内ASR失败，使用微信识别文字兜底: user={}, chars={}, reason={}",
                        userOpenId, recognition.trim().length(), asrException.getMessage());
                return recognition.trim();
            }
            throw asrException;
        }
    }
}
