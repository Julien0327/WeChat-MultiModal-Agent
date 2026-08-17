package com.group4.wxai.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.mp.bean.message.WxMpXmlMessage;
import me.chanjar.weixin.mp.bean.message.WxMpXmlOutMessage;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class MessageRouter {

    private final List<MessageHandler> handlers;

    public WxMpXmlOutMessage route(WxMpXmlMessage inMessage) {
        String msgType = inMessage.getMsgType();
        log.info("路由消息: msgType={}", msgType);

        for (MessageHandler handler : handlers) {
            if (handler.supports(msgType)) {
                try {
                    return handler.handle(inMessage);
                } catch (Exception e) {
                    log.error("消息处理异常: handler={}", handler.getClass().getSimpleName(), e);
                    return buildTextReply(inMessage, "处理消息时出现异常，请稍后重试。");
                }
            }
        }

        log.warn("无匹配的处理器: msgType={}", msgType);
        return buildTextReply(inMessage, "暂不支持该消息类型。");
    }

    private WxMpXmlOutMessage buildTextReply(WxMpXmlMessage inMessage, String content) {
        return WxMpXmlOutMessage.TEXT()
                .content(content)
                .fromUser(inMessage.getToUser())
                .toUser(inMessage.getFromUser())
                .build();
    }
}
