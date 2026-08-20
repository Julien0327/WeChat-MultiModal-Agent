package com.group4.wxai.service.wx;

import com.group4.wxai.model.SynthesizedAudio;
import com.group4.wxai.service.audio.AudioDownloadService;
import com.group4.wxai.service.ilink.ILinkClientManager;
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
    private final AudioDownloadService audioDownloadService;
    private final ILinkClientManager iLinkClientManager;

    public void sendTextMessage(String openid, String content) {
        trySendTextMessage(openid, content);
    }

    public boolean trySendTextMessage(String openid, String content) {
        try {
            if (iLinkClientManager.isILinkUser(openid)) {
                iLinkClientManager.sendText(openid, content);
                log.info("iLink 文本消息发送成功: to={}", openid);
                return true;
            }
            WxMpKefuMessage message = WxMpKefuMessage.TEXT()
                    .toUser(openid)
                    .content(content)
                    .build();
            wxMpService.getKefuService().sendKefuMessage(message);
            log.info("客服文本消息发送成功: to={}", openid);
            return true;
        } catch (Exception e) {
            log.error("发送客服文本消息失败: to={}", openid, e);
            return false;
        }
    }

    public void sendImageMessage(String openid, String imageUrl) {
        try {
            if (iLinkClientManager.isILinkUser(openid)) {
                byte[] imageData = wxMediaService.downloadFromUrl(imageUrl);
                iLinkClientManager.sendImage(openid, imageData, "generated-image.jpg", "");
                log.info("iLink 图片消息发送成功: to={}", openid);
                return;
            }
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

    public boolean sendVoiceMessage(String openid, SynthesizedAudio audio) {
        try {
            if (audio == null || audio.isEmpty()) {
                return false;
            }
            if (iLinkClientManager.isILinkUser(openid)) {
                String format = audio.format().isBlank() ? "mp3" : audio.format();
                iLinkClientManager.sendFile(openid, audio.data(), "语音回复." + format, "语音回复 MP3");
                log.info("iLink 音频文件发送成功: to={}, format={}", openid, format);
                return true;
            }
            String mediaId = wxMediaService.uploadVoice(audio.data(), audio.format());
            WxMpKefuMessage message = WxMpKefuMessage.VOICE()
                    .toUser(openid)
                    .mediaId(mediaId)
                    .build();
            wxMpService.getKefuService().sendKefuMessage(message);
            log.info("客服语音消息发送成功: to={}, mediaId={}", openid, mediaId);
            return true;
        } catch (Exception e) {
            log.error("发送客服语音消息失败: to={}", openid, e);
            return false;
        }
    }

    public boolean sendMp3DownloadLink(String openid, SynthesizedAudio audio) {
        return audioDownloadService.publishMp3(audio)
                .map(url -> iLinkClientManager.isILinkUser(openid)
                        ? sendILinkTextSafely(openid, "MP3 文件（30 分钟内有效）：\n" + url)
                        : sendTextMessageSafely(openid, "MP3 文件（30 分钟内有效）：\n" + url))
                .orElse(false);
    }

    private boolean sendILinkTextSafely(String userId, String content) {
        try {
            iLinkClientManager.sendText(userId, content);
            return true;
        } catch (Exception e) {
            log.error("发送 iLink MP3 下载链接失败: to={}", userId, e);
            return false;
        }
    }

    private boolean sendTextMessageSafely(String openid, String content) {
        try {
            WxMpKefuMessage message = WxMpKefuMessage.TEXT()
                    .toUser(openid)
                    .content(content)
                    .build();
            wxMpService.getKefuService().sendKefuMessage(message);
            log.info("客服 MP3 下载链接发送成功: to={}", openid);
            return true;
        } catch (Exception e) {
            log.error("发送客服 MP3 下载链接失败: to={}", openid, e);
            return false;
        }
    }
}
