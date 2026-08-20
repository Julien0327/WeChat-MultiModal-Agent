package com.group4.wxai.controller;

import com.group4.wxai.service.audio.AudioDownloadService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/wx/audio")
@RequiredArgsConstructor
public class AudioDownloadController {

    private final AudioDownloadService audioDownloadService;

    @GetMapping(value = "/{token}.mp3", produces = "audio/mpeg")
    public ResponseEntity<byte[]> download(@PathVariable String token) {
        return audioDownloadService.findMp3(token)
                .map(data -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType("audio/mpeg"))
                        .contentLength(data.length)
                        .cacheControl(CacheControl.noStore())
                        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                                .filename("语音回复.mp3", StandardCharsets.UTF_8)
                                .build().toString())
                        .body(data))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
