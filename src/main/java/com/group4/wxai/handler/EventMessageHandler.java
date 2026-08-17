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
                replyContent = "欢迎关注！我是智能客服助手，可以与你进行文字、图片和语音的交互。\n"
                        + "发送文字与我聊天，发送图片让我帮你分析，发送语音与我对话。";
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
