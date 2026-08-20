package com.group4.wxai.service.ilink;

import com.github.wechat.ilink.sdk.core.model.WeixinMessage;

import java.util.List;

public record ILinkMessagesReceived(List<WeixinMessage> messages) {

    public ILinkMessagesReceived {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }
}
