package com.group4.wxai.service.ilink;

import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.group4.wxai.service.TextProcessingService;
import com.group4.wxai.service.wx.WxMessageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ILinkInboundMessageServiceTests {

    @Mock
    private TextProcessingService textProcessingService;
    @Mock
    private ILinkMediaProcessingService mediaProcessingService;
    @Mock
    private WxMessageService messageService;

    private ILinkInboundMessageService service;

    @BeforeEach
    void setUp() {
        service = new ILinkInboundMessageService(
                textProcessingService, mediaProcessingService, messageService
        );
    }

    @Test
    void routesTextToExistingProcessingPipelineAndDeduplicatesMessageId() {
        WeixinMessage message = new WeixinMessage();
        message.setMessage_id(1001L);
        message.setFrom_user_id("user@im.wechat");
        message.setItem_list(List.of(MessageItem.text("江油明天天气")));
        ILinkMessagesReceived event = new ILinkMessagesReceived(List.of(message));

        service.onMessages(event);
        service.onMessages(event);

        verify(textProcessingService, times(1))
                .processAsync("user@im.wechat", "江油明天天气");
    }
}
