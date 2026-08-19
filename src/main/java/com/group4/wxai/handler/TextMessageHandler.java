package com.group4.wxai.handler;

import com.group4.wxai.service.TextProcessingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.mp.bean.message.WxMpXmlMessage;
import me.chanjar.weixin.mp.bean.message.WxMpXmlOutMessage;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TextMessageHandler implements MessageHandler {

    private final TextProcessingService textProcessingService;

    @Override
    public boolean supports(String msgType) {
        return "text".equals(msgType);
    }

    @Override
    public WxMpXmlOutMessage handle(WxMpXmlMessage inMessage) {
        String userOpenId = inMessage.getFromUser();
        String userContent = inMessage.getContent();
        log.info("文本消息: from={}, chars={}",
                userOpenId, userContent == null ? 0 : userContent.length());

        textProcessingService.processAsync(userOpenId, userContent);
        return null;
    }
}
