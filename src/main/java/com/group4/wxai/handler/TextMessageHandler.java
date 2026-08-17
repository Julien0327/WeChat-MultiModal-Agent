package com.group4.wxai.handler;

import com.group4.wxai.model.ChatContext;
import com.group4.wxai.service.llm.LlmService;
import com.group4.wxai.service.wx.WxMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.mp.bean.message.WxMpXmlMessage;
import me.chanjar.weixin.mp.bean.message.WxMpXmlOutMessage;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class TextMessageHandler implements MessageHandler {

    private final LlmService llmService;
    private final WxMessageService wxMessageService;

    private static final Pattern IMAGE_PATTERN = Pattern.compile(
            "^(画|生成|绘制|帮我画|画一张|画个|来一张|做一张|生成一张|生成个|画图|图片).+",
            Pattern.CASE_INSENSITIVE
    );

    @Override
    public boolean supports(String msgType) {
        return "text".equals(msgType);
    }

    @Override
    public WxMpXmlOutMessage handle(WxMpXmlMessage inMessage) {
        String userOpenId = inMessage.getFromUser();
        String userContent = inMessage.getContent();
        log.info("文本消息: from={}, content={}", userOpenId, userContent);

        processAsync(inMessage, userOpenId, userContent);
        return null;
    }

    @Async("llmAsyncExecutor")
    public void processAsync(WxMpXmlMessage inMessage, String userOpenId, String userContent) {
        try {
            if (isImageRequest(userContent)) {
                handleImageGeneration(userOpenId, userContent);
            } else {
                handleTextChat(userOpenId, userContent);
            }
        } catch (Exception e) {
            log.error("异步处理文本消息失败: user={}", userOpenId, e);
            wxMessageService.sendTextMessage(userOpenId, "抱歉，处理消息时出现错误，请稍后重试。");
        }
    }

    private boolean isImageRequest(String content) {
        return IMAGE_PATTERN.matcher(content).matches();
    }

    private void handleImageGeneration(String userOpenId, String userContent) {
        String prompt = extractImagePrompt(userContent);
        log.info("检测到图片生成请求: user={}, prompt={}", userOpenId, prompt);

        wxMessageService.sendTextMessage(userOpenId, "正在为您生成图片，请稍候...");

        String imageUrl = llmService.generateImage(prompt);
        if (imageUrl != null && !imageUrl.isEmpty()) {
            wxMessageService.sendImageMessage(userOpenId, imageUrl);
            ChatContext.addUserMessage(userOpenId, userContent);
            ChatContext.addAssistantMessage(userOpenId, "[图片] " + imageUrl);
        } else {
            wxMessageService.sendTextMessage(userOpenId, "图片生成失败，请稍后重试。");
        }
    }

    private void handleTextChat(String userOpenId, String userContent) {
        String reply = llmService.chatText(userOpenId, userContent, ChatContext.getHistory(userOpenId));
        ChatContext.addUserMessage(userOpenId, userContent);
        ChatContext.addAssistantMessage(userOpenId, reply);
        wxMessageService.sendTextMessage(userOpenId, reply);
    }

    private String extractImagePrompt(String content) {
        String prompt = content.replaceAll(
                "^(画|生成|绘制|帮我画|画一张|画个|来一张|做一张|生成一张|生成个|画图|图片)", ""
        ).trim();
        return prompt.isEmpty() ? content : prompt;
    }
}
