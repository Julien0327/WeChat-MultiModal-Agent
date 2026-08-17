package com.group4.wxai.controller;

import com.group4.wxai.handler.MessageRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.bean.message.WxMpXmlMessage;
import me.chanjar.weixin.mp.bean.message.WxMpXmlOutMessage;
import org.springframework.web.bind.annotation.*;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@RestController
@RequestMapping("/wx")
@RequiredArgsConstructor
public class WxMessageController {

    private final WxMpService wxMpService;
    private final MessageRouter messageRouter;

    private static final Set<String> processedMsgIds = ConcurrentHashMap.newKeySet();

    @GetMapping(value = "/msg", produces = "text/plain;charset=UTF-8")
    public String verify(@RequestParam("signature") String signature,
                         @RequestParam("timestamp") String timestamp,
                         @RequestParam("nonce") String nonce,
                         @RequestParam("echostr") String echostr) {
        log.info("收到微信服务器验证请求: timestamp={}, nonce={}", timestamp, nonce);
        if (wxMpService.checkSignature(timestamp, nonce, signature)) {
            log.info("签名验证通过");
            return echostr;
        }
        log.warn("签名验证失败");
        return "error";
    }

    @PostMapping(value = "/msg", produces = "application/xml;charset=UTF-8")
    public String handleMessage(@RequestBody String requestBody,
                                @RequestParam("signature") String signature,
                                @RequestParam("timestamp") String timestamp,
                                @RequestParam("nonce") String nonce,
                                @RequestParam(value = "encrypt_type", required = false) String encryptType,
                                @RequestParam(value = "msg_signature", required = false) String msgSignature) {
        log.info("收到微信消息: \n{}", requestBody);

        if (!wxMpService.checkSignature(timestamp, nonce, signature)) {
            log.warn("消息签名验证失败");
            return "error";
        }

        WxMpXmlMessage inMessage;
        if ("aes".equals(encryptType)) {
            inMessage = WxMpXmlMessage.fromEncryptedXml(requestBody, wxMpService.getWxMpConfigStorage(),
                    timestamp, nonce, msgSignature);
        } else {
            inMessage = WxMpXmlMessage.fromXml(requestBody);
        }

        log.info("解析消息: MsgType={}, FromUser={}, Content={}, MsgId={}",
                inMessage.getMsgType(), inMessage.getFromUser(), inMessage.getContent(), inMessage.getMsgId());

        Long msgId = inMessage.getMsgId();
        if (msgId != null && !processedMsgIds.add(String.valueOf(msgId))) {
            log.info("重复消息已忽略: MsgId={}", msgId);
            return "success";
        }
        if (processedMsgIds.size() > 1000) {
            processedMsgIds.clear();
        }

        WxMpXmlOutMessage outMessage = messageRouter.route(inMessage);
        if (outMessage == null) {
            return "success";
        }
        return outMessage.toXml();
    }
}
