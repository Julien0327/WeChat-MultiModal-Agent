package com.group4.wxai.service.wx;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.bean.kefu.WxMpKefuMessage;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class WxMessageService {

    private final WxMpService wxMpService;
    private final WxMediaService wxMediaService;

    public void sendTextMessage(String openid, String content) {
        try {
            WxMpKefuMessage message = WxMpKefuMessage.TEXT()
                    .toUser(openid)
                    .content(content)
                    .build();
            wxMpService.getKefuService().sendKefuMessage(message);
            log.info("客服文本消息发送成功: to={}", openid);
        } catch (Exception e) {
            log.error("发送客服文本消息失败: to={}", openid, e);
        }
    }

    public void sendImageMessage(String openid, String imageUrl) {
        try {
            String mediaId = wxMediaService.uploadImageFromUrl(imageUrl);
            WxMpKefuMessage message = WxMpKefuMessage.IMAGE()
                    .toUser(openid)
                    .mediaId(mediaId)
                    .build();
            wxMpService.getKefuService().sendKefuMessage(message);
            log.info("客服图片消息发送成功: to={}, mediaId={}", openid, mediaId);
        } catch (Exception e) {
            log.error("发送客服图片消息失败: to={}", openid, e);
            sendTextMessage(openid, "图片发送失败，图片地址: " + imageUrl);
        }
    }

    public void sendVoiceMessage(String openid, byte[] audioData) {
        try {
            String mediaId = wxMediaService.uploadVoice(audioData);
            WxMpKefuMessage message = WxMpKefuMessage.VOICE()
                    .toUser(openid)
                    .mediaId(mediaId)
                    .build();
            wxMpService.getKefuService().sendKefuMessage(message);
            log.info("客服语音消息发送成功: to={}, mediaId={}", openid, mediaId);
        } catch (Exception e) {
            log.error("发送客服语音消息失败: to={}", openid, e);
        }
    }
}
