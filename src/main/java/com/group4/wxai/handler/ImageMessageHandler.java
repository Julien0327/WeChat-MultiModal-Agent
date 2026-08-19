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

        WxMpXmlOutMessage pendingReply = WxMpXmlOutMessage.TEXT()
                .content("我先看一下这张图，稍等。")
                .fromUser(inMessage.getToUser())
                .toUser(userOpenId)
                .build();

        processAsync(inMessage, userOpenId, picUrl);
        return pendingReply;
    }

    @Async("llmAsyncExecutor")
    public void processAsync(WxMpXmlMessage inMessage, String userOpenId, String picUrl) {
        try {
            String description = llmService.chatWithImage(userOpenId,
                    "直接用自然的中文聊天文本说说你在图里看到了什么。不要使用模板开场、角色标签或占位符。",
                    picUrl);
            wxMessageService.sendTextMessage(userOpenId, description);
        } catch (Exception e) {
            log.error("异步处理图片消息失败: user={}", userOpenId, e);
            wxMessageService.sendTextMessage(userOpenId, "这张图刚才没看成功，等会儿再试一下。");
        }
    }
}
