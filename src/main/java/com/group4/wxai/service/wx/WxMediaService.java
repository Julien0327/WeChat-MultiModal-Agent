package com.group4.wxai.service.wx;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.common.bean.result.WxMediaUploadResult;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class WxMediaService {

    private final WxMpService wxMpService;
    private final OkHttpClient okHttpClient;

    public String uploadImageFromUrl(String imageUrl) throws Exception {
        log.info("从URL下载图片: {}", imageUrl);
        try (Response response = okHttpClient.newCall(new Request.Builder().url(imageUrl).build()).execute()) {
            if (!response.isSuccessful()) {
                throw new RuntimeException("下载图片失败: " + response.code());
            }
            byte[] imageData = response.body().bytes();
            return uploadImage(imageData, "jpg");
        }
    }

    public byte[] downloadTemporaryMedia(String mediaId) throws Exception {
        if (mediaId == null || mediaId.isBlank()) {
            throw new IllegalArgumentException("mediaId 不能为空");
        }

        File mediaFile = wxMpService.getMaterialService().mediaDownload(mediaId);
        if (mediaFile == null || !mediaFile.isFile()) {
            throw new IllegalStateException("微信临时素材下载失败");
        }

        try {
            byte[] data = Files.readAllBytes(mediaFile.toPath());
            log.info("微信临时素材下载成功: mediaId={}, size={} bytes", mediaId, data.length);
            return data;
        } finally {
            Files.deleteIfExists(mediaFile.toPath());
        }
    }

    public String uploadImage(byte[] imageData, String extension) throws Exception {
        Path tempFile = Files.createTempFile("wx_image_", "." + extension);
        Files.write(tempFile, imageData);
        File file = tempFile.toFile();
        try {
            WxMediaUploadResult result = wxMpService.getMaterialService()
                    .mediaUpload("image", file);
            log.info("图片素材上传成功: mediaId={}", result.getMediaId());
            return result.getMediaId();
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    public String uploadVoice(byte[] audioData, String format) throws Exception {
        String normalizedFormat = normalizeWechatVoiceFormat(format);
        Path tempFile = Files.createTempFile("wx_voice_", "." + normalizedFormat);
        Files.write(tempFile, audioData);
        File file = tempFile.toFile();
        try {
            WxMediaUploadResult result = wxMpService.getMaterialService()
                    .mediaUpload("voice", file);
            log.info("语音素材上传成功: mediaId={}", result.getMediaId());
            return result.getMediaId();
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private String normalizeWechatVoiceFormat(String format) {
        if (format == null) {
            throw new IllegalArgumentException("语音格式不能为空");
        }
        String normalized = format.trim().toLowerCase(Locale.ROOT);
        if ("mpeg".equals(normalized)) {
            normalized = "mp3";
        }
        if (!"mp3".equals(normalized) && !"amr".equals(normalized)) {
            throw new IllegalArgumentException("微信公众号语音回复仅支持 mp3 或 amr，当前格式: " + format);
        }
        return normalized;
    }
}
