package com.group4.wxai.service;

import com.group4.wxai.model.ChatContext;
import com.group4.wxai.model.IntentResult;
import com.group4.wxai.model.IntentType;
import com.group4.wxai.model.LlmMessage;
import com.group4.wxai.model.ReplyMode;
import com.group4.wxai.model.SynthesizedAudio;
import com.group4.wxai.service.intent.IntentClassifier;
import com.group4.wxai.service.llm.LlmService;
import com.group4.wxai.service.tool.PlaceSearchTool;
import com.group4.wxai.service.tool.ToolRegistry;
import com.group4.wxai.service.tool.WeatherTool;
import com.group4.wxai.service.wx.WxMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class TextProcessingService {

    private static final String HELP_MESSAGE = """
            有事直接说就行。我可以正常聊天和回答问题，也能查天气、找地点、生成图片、看你发来的图片，还能听懂语音。

            比如你可以问“成都明天天气怎么样”“绵阳有哪些景点”，或者说“帮我画一只戴墨镜的猫”。我默认打字回复；想听语音就说“用语音回答我”。要重新开始对话，可以说“清空聊天记录”。
            """;

    private final IntentClassifier intentClassifier;
    private final LlmService llmService;
    private final ToolRegistry toolRegistry;
    private final WxMessageService wxMessageService;

    @Async("llmAsyncExecutor")
    public void processAsync(String userOpenId, String userContent) {
        process(userOpenId, userContent, ReplyMode.TEXT);
    }

    public void process(String userOpenId, String userContent, ReplyMode defaultReplyMode) {
        try {
            List<LlmMessage> history = List.copyOf(ChatContext.getHistory(userOpenId));
            IntentResult intent = intentClassifier.classify(userContent, history);
            ReplyMode replyMode = resolveReplyMode(intent.replyMode(), defaultReplyMode);
            log.info("意图识别完成: user={}, intent={}, confidence={}, replyMode={}, needsClarification={}",
                    userOpenId, intent.intent(), intent.confidence(), replyMode, intent.needsClarification());

            if (intent.needsClarification() || intent.intent() == IntentType.UNKNOWN) {
                sendClarification(userOpenId, intent, replyMode);
                return;
            }

            switch (intent.intent()) {
                case WEATHER -> handleWeather(userOpenId, userContent, intent, replyMode);
                case PLACE_SEARCH -> handlePlaceSearch(userOpenId, userContent, intent, replyMode);
                case GENERATE_IMAGE -> handleImageGeneration(userOpenId, userContent, intent.slot("prompt"));
                case CLEAR_CONTEXT -> clearContext(userOpenId, replyMode);
                case HELP -> sendReply(userOpenId, HELP_MESSAGE.strip(), replyMode);
                case CHAT -> handleTextChat(userOpenId, userContent, history, replyMode);
                case UNKNOWN -> sendClarification(userOpenId, intent, replyMode);
            }
        } catch (Exception e) {
            log.error("异步处理文本消息失败: user={}", userOpenId, e);
            wxMessageService.sendTextMessage(userOpenId, "刚才没处理成功，等会儿再试一下。");
        }
    }

    private void handleWeather(String userOpenId, String userContent, IntentResult intent, ReplyMode replyMode) {
        executeTool(userOpenId, userContent, WeatherTool.NAME, Map.of(
                "location", intent.slot("location"),
                "date", intent.slot("date")
        ), replyMode, "天气服务暂时不可用，请稍后重试。");
    }

    private void handlePlaceSearch(String userOpenId, String userContent, IntentResult intent, ReplyMode replyMode) {
        executeTool(userOpenId, userContent, PlaceSearchTool.NAME, Map.of(
                "location", intent.slot("location"),
                "keyword", intent.slot("keyword")
        ), replyMode, "地点搜索服务暂时不可用，请稍后重试。");
    }

    private void executeTool(String userOpenId, String userContent, String toolName,
                             Map<String, String> arguments, ReplyMode replyMode, String failureMessage) {
        try {
            String reply = toolRegistry.execute(toolName, arguments);
            ChatContext.addUserMessage(userOpenId, userContent);
            ChatContext.addAssistantMessage(userOpenId, reply);
            sendReply(userOpenId, reply, replyMode);
        } catch (IllegalArgumentException e) {
            sendReply(userOpenId, e.getMessage(), replyMode);
        } catch (Exception e) {
            log.error("执行AI工具失败: user={}, tool={}", userOpenId, toolName, e);
            sendReply(userOpenId, failureMessage, replyMode);
        }
    }

    private void handleImageGeneration(String userOpenId, String userContent, String prompt) {
        String effectivePrompt = prompt == null || prompt.isBlank() ? userContent : prompt.trim();
        log.info("执行图片生成意图: user={}, promptChars={}", userOpenId, effectivePrompt.length());
        wxMessageService.sendTextMessage(userOpenId, "正在为您生成图片，请稍候...");

        String imageUrl = llmService.generateImage(effectivePrompt);
        if (imageUrl != null && !imageUrl.isBlank()) {
            wxMessageService.sendImageMessage(userOpenId, imageUrl);
            ChatContext.addUserMessage(userOpenId, userContent);
            ChatContext.addAssistantMessage(userOpenId, "[图片] " + imageUrl);
        } else {
            wxMessageService.sendTextMessage(userOpenId, "图片生成失败，请稍后重试。");
        }
    }

    private void handleTextChat(String userOpenId, String userContent, List<LlmMessage> history,
                                ReplyMode replyMode) {
        String reply = llmService.chatText(userOpenId, userContent, history);
        ChatContext.addUserMessage(userOpenId, userContent);
        ChatContext.addAssistantMessage(userOpenId, reply);
        sendReply(userOpenId, reply, replyMode);
    }

    private void clearContext(String userOpenId, ReplyMode replyMode) {
        ChatContext.clear(userOpenId);
        sendReply(userOpenId, "已清除本次对话记录，我们可以重新开始。", replyMode);
    }

    private void sendClarification(String userOpenId, IntentResult intent, ReplyMode replyMode) {
        String question = intent.clarificationQuestion().isBlank()
                ? "我还不确定你想让我做什么，请再具体说明一下。"
                : intent.clarificationQuestion();
        sendReply(userOpenId, question, replyMode);
    }

    private ReplyMode resolveReplyMode(ReplyMode requestedMode, ReplyMode defaultMode) {
        if (requestedMode != null && requestedMode != ReplyMode.AUTO) {
            return requestedMode;
        }
        return defaultMode == null || defaultMode == ReplyMode.AUTO ? ReplyMode.TEXT : defaultMode;
    }

    private void sendReply(String userOpenId, String content, ReplyMode replyMode) {
        if (replyMode == ReplyMode.VOICE) {
            SynthesizedAudio audio = llmService.textToSpeech(content);
            if (audio != null && !audio.isEmpty() && wxMessageService.sendVoiceMessage(userOpenId, audio)) {
                return;
            }
            log.warn("语音回复失败，降级为文字: user={}", userOpenId);
        }
        wxMessageService.sendTextMessage(userOpenId, content);
    }
}
