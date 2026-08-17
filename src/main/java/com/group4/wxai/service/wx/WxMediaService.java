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

    public String uploadVoice(byte[] audioData) throws Exception {
        Path tempFile = Files.createTempFile("wx_voice_", ".amr");
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
}
