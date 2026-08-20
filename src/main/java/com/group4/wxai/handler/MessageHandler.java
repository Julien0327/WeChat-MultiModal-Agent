package com.group4.wxai.handler;

import me.chanjar.weixin.mp.bean.message.WxMpXmlMessage;
import me.chanjar.weixin.mp.bean.message.WxMpXmlOutMessage;

public interface MessageHandler {

    boolean supports(String msgType);

    WxMpXmlOutMessage handle(WxMpXmlMessage inMessage);
}
