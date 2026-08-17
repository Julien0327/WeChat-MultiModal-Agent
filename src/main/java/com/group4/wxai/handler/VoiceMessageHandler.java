package com.group4.wxai.handler;

import com.group4.wxai.model.ChatContext;
import com.group4.wxai.service.llm.LlmService;
import com.group4.wxai.service.wx.WxMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.mp.bean.message.WxMpXmlMessage;
import me.chanjar.weixin.mp.bean.message.WxMpXmlOutMessage;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class VoiceMessageHandler implements MessageHandler {

    private final LlmService llmService;
    private final WxMessageService wxMessageService;

    @Override
    public boolean supports(String msgType) {
        return "voice".equals(msgType);
    }

    @Override
    public WxMpXmlOutMessage handle(WxMpXmlMessage inMessage) {
        String userOpenId = inMessage.getFromUser();
        String recognition = inMessage.getRecognition();
        log.info("语音消息: from={}, recognition={}", userOpenId, recognition);

        WxMpXmlOutMessage placeholder = WxMpXmlOutMessage.TEXT()
                .content("正在处理语音消息，请稍候...")
                .fromUser(inMessage.getToUser())
                .toUser(userOpenId)
                .build();

        processAsync(inMessage, userOpenId, recognition);
        return placeholder;
    }

    @Async("llmAsyncExecutor")
    public void processAsync(WxMpXmlMessage inMessage, String userOpenId, String recognition) {
        try {
            String text = recognition != null ? recognition : "（无法识别语音内容）";
            ChatContext.addUserMessage(userOpenId, text);

            String reply = llmService.chatText(userOpenId, text, ChatContext.getHistory(userOpenId));
            ChatContext.addAssistantMessage(userOpenId, reply);

            byte[] audio = llmService.textToSpeech(reply);
            if (audio != null && audio.length > 0) {
                wxMessageService.sendVoiceMessage(userOpenId, audio);
            } else {
                wxMessageService.sendTextMessage(userOpenId, reply);
            }
        } catch (Exception e) {
            log.error("异步处理语音消息失败: user={}", userOpenId, e);
            wxMessageService.sendTextMessage(userOpenId, "抱歉，语音处理出现错误，请稍后重试。");
        }
    }
}
