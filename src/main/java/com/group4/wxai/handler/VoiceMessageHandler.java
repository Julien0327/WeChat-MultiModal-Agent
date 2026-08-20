package com.group4.wxai.handler;

import com.group4.wxai.service.VoiceProcessingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.mp.bean.message.WxMpXmlMessage;
import me.chanjar.weixin.mp.bean.message.WxMpXmlOutMessage;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class VoiceMessageHandler implements MessageHandler {

    private final VoiceProcessingService voiceProcessingService;

    @Override
    public boolean supports(String msgType) {
        return "voice".equals(msgType);
    }

    @Override
    public WxMpXmlOutMessage handle(WxMpXmlMessage inMessage) {
        String userOpenId = inMessage.getFromUser();
        String recognition = inMessage.getRecognition();
        String mediaId = inMessage.getMediaId();
        String format = inMessage.getFormat();
        log.info("语音消息: from={}, format={}, hasRecognition={}",
                userOpenId, format, recognition != null && !recognition.isBlank());

        WxMpXmlOutMessage pendingReply = WxMpXmlOutMessage.TEXT()
                .content("我先听一下，稍等。")
                .fromUser(inMessage.getToUser())
                .toUser(userOpenId)
                .build();

        voiceProcessingService.processAsync(userOpenId, recognition, mediaId, format);
        return pendingReply;
    }
}
