package com.group4.wxai.handler;

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
public class ImageMessageHandler implements MessageHandler {

    private final LlmService llmService;
    private final WxMessageService wxMessageService;

    @Override
    public boolean supports(String msgType) {
        return "image".equals(msgType);
    }

    @Override
    public WxMpXmlOutMessage handle(WxMpXmlMessage inMessage) {
        String userOpenId = inMessage.getFromUser();
        String picUrl = inMessage.getPicUrl();
        log.info("图片消息: from={}, picUrl={}", userOpenId, picUrl);

        WxMpXmlOutMessage placeholder = WxMpXmlOutMessage.TEXT()
                .content("正在分析图片，请稍候...")
                .fromUser(inMessage.getToUser())
                .toUser(userOpenId)
                .build();

        processAsync(inMessage, userOpenId, picUrl);
        return placeholder;
    }

    @Async("llmAsyncExecutor")
    public void processAsync(WxMpXmlMessage inMessage, String userOpenId, String picUrl) {
        try {
            String description = llmService.chatWithImage(userOpenId, "请描述这张图片的内容", picUrl);
            wxMessageService.sendTextMessage(userOpenId, description);
        } catch (Exception e) {
            log.error("异步处理图片消息失败: user={}", userOpenId, e);
            wxMessageService.sendTextMessage(userOpenId, "抱歉，图片分析出现错误，请稍后重试。");
        }
    }
}
