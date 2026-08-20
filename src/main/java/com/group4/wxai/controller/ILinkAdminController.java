package com.group4.wxai.controller;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;
import com.group4.wxai.service.ilink.ILinkClientManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.util.Map;

@RestController
@RequestMapping("/ilink/admin")
@RequiredArgsConstructor
public class ILinkAdminController {

    private static final String ADMIN_HEADER = "X-ILink-Admin-Token";
    private final ILinkClientManager clientManager;

    @GetMapping("/status")
    public ILinkClientManager.ILinkStatus status(@RequestHeader(ADMIN_HEADER) String adminToken) {
        clientManager.requireAdminToken(adminToken);
        return clientManager.status();
    }

    @PostMapping("/login")
    public Map<String, String> login(
            @RequestHeader(ADMIN_HEADER) String adminToken,
            @RequestParam(defaultValue = "false") boolean force) {
        clientManager.requireAdminToken(adminToken);
        return Map.of(
                "qrCodeContent", clientManager.startLogin(force),
                "message", "请在 3 分钟内把 qrCodeContent 渲染为二维码并用微信扫码"
        );
    }

    @PostMapping(value = "/login.png", produces = MediaType.IMAGE_PNG_VALUE)
    public byte[] loginQrImage(
            @RequestHeader(ADMIN_HEADER) String adminToken,
            @RequestParam(defaultValue = "false") boolean force) throws Exception {
        clientManager.requireAdminToken(adminToken);
        String qrCodeContent = clientManager.startLogin(force);
        var matrix = new QRCodeWriter().encode(qrCodeContent, BarcodeFormat.QR_CODE, 420, 420);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            MatrixToImageWriter.writeToStream(matrix, "PNG", output);
            return output.toByteArray();
        }
    }

    @ExceptionHandler(SecurityException.class)
    public void unauthorized(SecurityException exception) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, exception.getMessage());
    }
}
