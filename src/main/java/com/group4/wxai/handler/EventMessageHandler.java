package com.group4.wxai.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.mp.bean.message.WxMpXmlMessage;
import me.chanjar.weixin.mp.bean.message.WxMpXmlOutMessage;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class EventMessageHandler implements MessageHandler {

    @Override
    public boolean supports(String msgType) {
        return "event".equals(msgType);
    }

    @Override
    public WxMpXmlOutMessage handle(WxMpXmlMessage inMessage) {
        String event = inMessage.getEvent();
        String userOpenId = inMessage.getFromUser();
        log.info("事件消息: from={}, event={}", userOpenId, event);

        String replyContent;
        switch (event) {
            case "subscribe":
                replyContent = "你好，有事直接跟我说就行。平时可以聊聊天、问问题，也可以查天气、找地点、生成图片。"
                        + "你发图片我能看，发语音我也能听；想让我用语音回你，就直接说一声。";
                break;
            case "unsubscribe":
                log.info("用户取消关注: {}", userOpenId);
                return null;
            case "CLICK":
                replyContent = "收到菜单点击事件: " + inMessage.getEventKey();
                break;
            default:
                replyContent = "收到事件: " + event;
                break;
        }

        return WxMpXmlOutMessage.TEXT()
                .content(replyContent)
                .fromUser(inMessage.getToUser())
                .toUser(userOpenId)
                .build();
    }
}
