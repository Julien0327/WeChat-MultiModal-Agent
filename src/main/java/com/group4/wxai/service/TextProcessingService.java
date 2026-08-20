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
import com.group4.wxai.service.tool.TripPlanningTool;
import com.group4.wxai.service.tool.WeatherTool;
import com.group4.wxai.service.tool.WebSearchTool;
import com.group4.wxai.service.tool.WorldClockTool;
import com.group4.wxai.service.wx.WxMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class TextProcessingService {

    private static final Pattern WEATHER_FOLLOW_UP_PATTERN = Pattern.compile(
            "^(?:那|那么|再查(?:一下)?|再看(?:一下)?)\\s*"
                    + "(今天|明天|后天|(?:未来|最近|近)(?:几|三|3|七|7)天|本周|这周)"
                    + "(?:的)?(?:天气|预报)?(?:呢|怎么样|如何)?[？?]?$"
    );

    private static final String HELP_MESSAGE = """
            有事直接说就行。我可以正常聊天和回答问题，也能查天气、找地点、看世界时间、联网搜索、规划出行、计算、翻译、管理待办和账单、设置提醒、查快递、生成图片、看你发来的图片，还能听懂语音。

            比如你可以问“成都明天天气怎么样”“绵阳有哪些景点”“联网搜索最近的科技新闻”，或者说“帮我规划周六从江油去成都的一日游”。出行规划会先确认出发地、目的地、时间和时长，信息不全不会乱查。你也可以说“帮我画一只戴墨镜的猫”。我默认打字回复；想听语音就说“用语音回答我”，我会发送音频文件和 MP3 下载链接。要重新开始对话，可以说“清空聊天记录”。
            """;

    private static final Pattern SAFE_WEATHER_LOCATION_ANSWER = Pattern.compile(
            "^[\\p{L}\\p{N} .,'·-]{2,80}$"
    );
    private static final Pattern NON_LOCATION_ANSWER = Pattern.compile(
            ".*(?:今天|明天|后天|天气|几点|为什么|怎么|如何|聊天|搜索|查询|画图|生成|[？?]).*"
    );

    private final IntentClassifier intentClassifier;
    private final LlmService llmService;
    private final ToolRegistry toolRegistry;
    private final TripPlanningService tripPlanningService;
    private final WxMessageService wxMessageService;
    private final Map<String, Object> userProcessingLocks = new ConcurrentHashMap<>();

    @Async("llmAsyncExecutor")
    public void processAsync(String userOpenId, String userContent) {
        process(userOpenId, userContent, ReplyMode.TEXT);
    }

    public void process(String userOpenId, String userContent, ReplyMode defaultReplyMode) {
        Object userLock = userProcessingLocks.computeIfAbsent(userOpenId, ignored -> new Object());
        synchronized (userLock) {
            processSerially(userOpenId, userContent, defaultReplyMode);
        }
    }

    private void processSerially(String userOpenId, String userContent, ReplyMode defaultReplyMode) {
        try {
            List<LlmMessage> history = ChatContext.getHistory(userOpenId);
            IntentResult intent = intentClassifier.classify(userContent, history);
            ReplyMode replyMode = resolveReplyMode(intent.replyMode(), defaultReplyMode);

            if (intent.intent() == IntentType.TRIP_PLAN
                    || tripPlanningService.shouldHandlePendingAnswer(userOpenId, userContent, intent)) {
                logIntent(userOpenId, intent, replyMode);
                handleTripPlanning(userOpenId, userContent, intent, replyMode);
                return;
            }

            IntentResult pendingWeather = resolvePendingWeatherLocation(userOpenId, userContent, intent);
            if (pendingWeather != null) {
                intent = pendingWeather;
                replyMode = resolveReplyMode(intent.replyMode(), defaultReplyMode);
            } else if (intent.intent() == IntentType.CHAT || intent.intent() == IntentType.UNKNOWN) {
                IntentResult weatherFollowUp = resolveContextualWeatherFollowUp(userOpenId, userContent);
                if (weatherFollowUp != null) {
                    intent = weatherFollowUp;
                    replyMode = resolveReplyMode(intent.replyMode(), defaultReplyMode);
                }
            }

            logIntent(userOpenId, intent, replyMode);

            if (intent.needsClarification() || intent.intent() == IntentType.UNKNOWN) {
                sendClarification(userOpenId, intent, replyMode);
                return;
            }

            switch (intent.intent()) {
                case WEATHER -> handleWeather(userOpenId, userContent, intent, replyMode);
                case PLACE_SEARCH -> handlePlaceSearch(userOpenId, userContent, intent, replyMode);
                case WEB_SEARCH -> handleWebSearch(userOpenId, userContent, intent, replyMode);
                case WORLD_CLOCK -> handleWorldClock(userOpenId, userContent, intent, replyMode);
                case TRIP_PLAN -> handleTripPlanning(userOpenId, userContent, intent, replyMode);
                case TEAM_TOOL -> handleTeamTool(userOpenId, userContent, intent, replyMode);
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
        if (intent.slot("location").isBlank()) {
            String date = intent.slot("date").isBlank() ? "TODAY" : intent.slot("date");
            String question = "你要查哪个城市或地区的天气？请告诉我具体地点。";
            ChatContext.rememberPendingWeather(userOpenId, date);
            ChatContext.addUserMessage(userOpenId, userContent);
            ChatContext.addAssistantMessage(userOpenId, question);
            sendReply(userOpenId, question, replyMode);
            return;
        }
        ChatContext.clearPendingWeather(userOpenId);
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

    private void handleWebSearch(String userOpenId, String userContent, IntentResult intent, ReplyMode replyMode) {
        executeTool(userOpenId, userContent, WebSearchTool.NAME, Map.of(
                "query", intent.slot("query")
        ), replyMode, "联网搜索暂时不可用，请稍后重试。");
    }

    private void handleWorldClock(String userOpenId, String userContent, IntentResult intent, ReplyMode replyMode) {
        executeTool(userOpenId, userContent, WorldClockTool.NAME, Map.of(
                "location", intent.slot("location")
        ), replyMode, "世界时钟暂时不可用，请稍后重试。");
    }

    private void handleTripPlanning(String userOpenId, String userContent, IntentResult intent,
                                    ReplyMode replyMode) {
        try {
            TripPlanningService.TripPlanTurn turn = tripPlanningService.handleTurn(userOpenId, userContent, intent);
            ChatContext.addUserMessage(userOpenId, userContent);
            ChatContext.addAssistantMessage(userOpenId, turn.reply());
            sendReply(userOpenId, turn.reply(), replyMode);
        } catch (IllegalArgumentException e) {
            sendReply(userOpenId, e.getMessage(), replyMode);
        } catch (Exception e) {
            log.error("执行出行规划失败: user={}", userOpenId, e);
            sendReply(userOpenId, "出行资料暂时没有查全，请稍后再试；我不会用不完整的信息替你乱编行程。", replyMode);
        }
    }

    private void handleTeamTool(String userOpenId, String userContent, IntentResult intent,
                                ReplyMode replyMode) {
        String toolName = intent.slot("tool");
        Map<String, String> arguments = new java.util.LinkedHashMap<>(intent.slots());
        arguments.remove("tool");
        try {
            String reply = toolRegistry.execute(toolName, userOpenId, arguments);
            ChatContext.addUserMessage(userOpenId, userContent);
            ChatContext.addAssistantMessage(userOpenId, reply);
            sendReply(userOpenId, reply, replyMode);
        } catch (IllegalArgumentException | IllegalStateException e) {
            sendReply(userOpenId, e.getMessage(), replyMode);
        } catch (Exception e) {
            log.error("执行团队工具失败: user={}, tool={}", userOpenId, toolName, e);
            sendReply(userOpenId, "这个工具暂时不可用，请稍后重试。", replyMode);
        }
    }

    private void executeTool(String userOpenId, String userContent, String toolName,
                             Map<String, String> arguments, ReplyMode replyMode, String failureMessage) {
        try {
            String reply = toolRegistry.execute(toolName, arguments);
            if (WeatherTool.NAME.equals(toolName)) {
                ChatContext.rememberLocation(userOpenId, arguments.get("location"));
            }
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

    private IntentResult resolveContextualWeatherFollowUp(String userOpenId, String userContent) {
        if (userContent == null) {
            return null;
        }
        Matcher matcher = WEATHER_FOLLOW_UP_PATTERN.matcher(userContent.trim());
        if (!matcher.matches()) {
            return null;
        }
        return ChatContext.getLastLocation(userOpenId)
                .map(location -> IntentResult.weather(1, location, normalizeFollowUpDate(matcher.group(1))))
                .orElse(null);
    }

    private IntentResult resolvePendingWeatherLocation(String userOpenId, String userContent,
                                                       IntentResult classifiedIntent) {
        return ChatContext.getPendingWeatherDate(userOpenId)
                .filter(ignored -> classifiedIntent.intent() == IntentType.CHAT
                        || classifiedIntent.intent() == IntentType.UNKNOWN)
                .filter(ignored -> isSafeWeatherLocationAnswer(userContent))
                .map(date -> IntentResult.weather(1, userContent.trim(), date))
                .orElse(null);
    }

    private boolean isSafeWeatherLocationAnswer(String content) {
        if (content == null) {
            return false;
        }
        String normalized = content.trim();
        return SAFE_WEATHER_LOCATION_ANSWER.matcher(normalized).matches()
                && !NON_LOCATION_ANSWER.matcher(normalized).matches()
                && !Set.of("这里", "那里", "附近", "随便", "你猜").contains(normalized);
    }

    private void logIntent(String userOpenId, IntentResult intent, ReplyMode replyMode) {
        log.info("意图识别完成: user={}, intent={}, confidence={}, replyMode={}, needsClarification={}",
                userOpenId, intent.intent(), intent.confidence(), replyMode, intent.needsClarification());
    }

    private String normalizeFollowUpDate(String expression) {
        return switch (expression) {
            case "明天" -> "TOMORROW";
            case "后天" -> "DAY_AFTER_TOMORROW";
            case "未来七天", "未来7天", "最近七天", "最近7天", "近七天", "近7天", "本周", "这周" -> "NEXT_7_DAYS";
            case "未来几天", "最近几天", "近几天", "未来三天", "未来3天", "最近三天", "最近3天", "近三天", "近3天" -> "NEXT_3_DAYS";
            default -> "TODAY";
        };
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
        tripPlanningService.clear(userOpenId);
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
            if (audio != null && !audio.isEmpty()) {
                boolean voiceSent = wxMessageService.sendVoiceMessage(userOpenId, audio);
                boolean mp3LinkSent = wxMessageService.sendMp3DownloadLink(userOpenId, audio);
                if (voiceSent || mp3LinkSent) {
                    if (!voiceSent || !mp3LinkSent) {
                        log.warn("语音回复未完整发送: user={}, voiceSent={}, mp3LinkSent={}",
                                userOpenId, voiceSent, mp3LinkSent);
                    }
                    return;
                }
            }
            log.warn("语音和 MP3 下载链接均发送失败，降级为文字: user={}", userOpenId);
        }
        wxMessageService.sendTextMessage(userOpenId, content);
    }
}
