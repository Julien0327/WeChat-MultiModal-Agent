package com.group4.wxai.service.audio;

import com.group4.wxai.config.AudioDownloadProperties;
import com.group4.wxai.model.SynthesizedAudio;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioDownloadServiceTests {

    @Test
    void publishesAndReadsTemporaryMp3() {
        AudioDownloadProperties properties = new AudioDownloadProperties();
        properties.setPublicBaseUrl("https://wx.example.test/");
        properties.setTtl(Duration.ofMinutes(30));
        AudioDownloadService service = new AudioDownloadService(properties);
        byte[] source = {1, 2, 3};

        String url = service.publishMp3(new SynthesizedAudio(source, "mp3")).orElseThrow();
        String token = url.substring(url.lastIndexOf('/') + 1, url.length() - ".mp3".length());

        assertTrue(url.startsWith("https://wx.example.test/wx/audio/"));
        assertArrayEquals(source, service.findMp3(token).orElseThrow());
    }

    @Test
    void refusesToPublishWithoutPublicBaseUrl() {
        AudioDownloadService service = new AudioDownloadService(new AudioDownloadProperties());

        assertTrue(service.publishMp3(new SynthesizedAudio(new byte[]{1}, "mp3")).isEmpty());
    }
}
