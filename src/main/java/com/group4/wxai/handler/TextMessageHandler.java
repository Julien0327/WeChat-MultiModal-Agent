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
public class TextMessageHandler implements MessageHandler {

    private final LlmService llmService;
    private final WxMessageService wxMessageService;

    @Override
    public boolean supports(String msgType) {
        return "text".equals(msgType);
    }

    @Override
    public WxMpXmlOutMessage handle(WxMpXmlMessage inMessage) {
        String userOpenId = inMessage.getFromUser();
        String userContent = inMessage.getContent();
        log.info("文本消息: from={}, content={}", userOpenId, userContent);

        ChatContext.addUserMessage(userOpenId, userContent);

        WxMpXmlOutMessage placeholder = WxMpXmlOutMessage.TEXT()
                .content("正在思考中，请稍候...")
                .fromUser(inMessage.getToUser())
                .toUser(userOpenId)
                .build();

        processAsync(inMessage, userOpenId, userContent);
        return placeholder;
    }

    @Async("llmAsyncExecutor")
    public void processAsync(WxMpXmlMessage inMessage, String userOpenId, String userContent) {
        try {
            String reply = llmService.chatText(userOpenId, userContent, ChatContext.getHistory(userOpenId));
            ChatContext.addAssistantMessage(userOpenId, reply);
            wxMessageService.sendTextMessage(userOpenId, reply);
        } catch (Exception e) {
            log.error("异步处理文本消息失败: user={}", userOpenId, e);
            wxMessageService.sendTextMessage(userOpenId, "抱歉，处理消息时出现错误，请稍后重试。");
        }
    }
}
