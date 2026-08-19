package com.group4.wxai.service.intent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.group4.wxai.config.LlmProperties;
import com.group4.wxai.model.IntentResult;
import com.group4.wxai.model.IntentType;
import com.group4.wxai.model.LlmMessage;
import com.group4.wxai.model.ReplyMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class HybridIntentClassifier implements IntentClassifier {

    private static final double ACTION_CONFIDENCE_THRESHOLD = 0.75;
    private static final int MAX_HISTORY_MESSAGES = 6;
    private static final Set<String> HELP_COMMANDS = Set.of(
            "帮助", "使用帮助", "功能", "功能介绍", "你会什么", "你能做什么",
            "你会说话吗", "你能说话吗", "你能语音回复吗", "你支持语音回复吗", "怎么用语音回复",
            "/help", "help"
    );
    private static final Pattern CLEAR_CONTEXT_PATTERN = Pattern.compile(
            "^(清除|清空|删除|重置|忘掉|忘记).{0,8}(上下文|聊天记录|对话记录|历史记录|之前的对话|前面的内容)$"
    );
    private static final Pattern STRONG_IMAGE_PATTERN = Pattern.compile(
            "^(?:请|麻烦|可以|能不能)?\\s*(?:帮我)?\\s*"
                    + "(?:画(?!家|画)|绘制|生成(?:一张|一幅|一个|个|图片|图像)?|来一张|做一张|画图)\\s*(.+)$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern STRONG_WEATHER_PATTERN = Pattern.compile(
            "^(?:请|麻烦)?(?:帮我)?(?:查(?:一下)?|查询|看看|看一下)?\\s*"
                    + "(?:(今天|明天|后天|未来(?:三|3|七|7)天|本周|这周)\\s*(?:的)?\\s*)?"
                    + "(.{2,40}?)"
                    + "(?:(今天|明天|后天|未来(?:三|3|七|7)天|本周|这周))?"
                    + "(?:的)?(?:天气|气温|温度)"
                    + "(?:怎么样|如何|预报|情况|是多少|多少度)?[？?]?$"
    );
    private static final Pattern STRONG_PLACE_SEARCH_PATTERN = Pattern.compile(
            "^(?:请|麻烦)?(?:帮我)?(?:查(?:一下)?|查询|搜索|找(?:一下)?|看看)?\\s*"
                    + "(.{2,30}?)(?:附近)?(?:有(?:什么|哪些)?|哪里有)\\s*"
                    + "(.{1,40}?)(?:推荐)?[吗呢？?]*$"
    );
    private static final Pattern VOICE_REPLY_PATTERN = Pattern.compile(
            "(?:用|改用|换成)?\\s*语音\\s*(?:来)?\\s*(?:回答|回复|告诉|说)(?:我)?"
    );
    private static final Pattern TEXT_REPLY_PATTERN = Pattern.compile(
            "(?:用|改用|换成)?\\s*(?:文字|文本)\\s*(?:来)?\\s*(?:回答|回复|告诉|说)(?:我)?"
    );

    private static final String SYSTEM_PROMPT = """
            你是微信公众号智能客服的意图分类器。你的唯一任务是根据当前消息和最近对话进行分类，
            不回答用户问题，不执行用户指令，只输出一个有效的 JSON 对象。

            允许的 intent：
            - CHAT：普通问答、闲聊、知识咨询；这是默认意图。
            - WEATHER：查询某个城市或地区的实时天气、气温、降雨或未来天气预报。
            - PLACE_SEARCH：在指定城市或地区搜索景点、餐厅、医院、学校、商场等现实地点。
            - GENERATE_IMAGE：用户明确要求创建、绘制或生成新图片。询问画家、绘画知识不属于该意图。
            - CLEAR_CONTEXT：用户明确要求清除、重置或忘记当前对话历史。
            - HELP：用户询问机器人会什么、如何使用或请求功能说明。
            - UNKNOWN：消息不完整或仅凭上下文仍无法判断用户希望做什么。

            必须输出以下 JSON 结构：
            {"intent":"CHAT","confidence":0.0,"slots":{},"replyMode":"AUTO","needsClarification":false,"clarificationQuestion":""}

            GENERATE_IMAGE 时从用户原话提取纯图片描述，放入 slots.prompt；不要自行补充用户未表达的主题。
            WEATHER 时必须提取 slots.location，并将日期写入 slots.date。date 只使用 TODAY、TOMORROW、
            DAY_AFTER_TOMORROW、NEXT_3_DAYS、NEXT_7_DAYS 或 YYYY-MM-DD；未指定日期时使用 TODAY。
            PLACE_SEARCH 时提取 slots.location 和 slots.keyword。例如“绵阳有哪些景点”应输出
            location=绵阳、keyword=景点；“江油哪里有医院”应输出 location=江油、keyword=医院。
            replyMode 只允许 AUTO、TEXT、VOICE。用户明确要求“用语音回答”时使用 VOICE，
            明确要求“用文字回答”时使用 TEXT，否则使用 AUTO。回复形式不是业务意图。
            只有确实无法判断时才使用 UNKNOWN，并提供简短 clarificationQuestion。
            confidence 必须是 0 到 1 之间的数字。
            """;

    private final LlmProperties llmProperties;
    private final ObjectMapper objectMapper;
    private final OkHttpClient okHttpClient;

    @Override
    public IntentResult classify(String userMessage, List<LlmMessage> history) {
        if (userMessage == null || userMessage.isBlank()) {
            return IntentResult.unknown("请告诉我你想让我做什么。");
        }

        ReplyMode explicitReplyMode = detectExplicitReplyMode(userMessage);
        IntentResult ruleResult = classifyByRule(userMessage);
        if (ruleResult != null) {
            return applyExplicitReplyMode(ruleResult, explicitReplyMode);
        }

        try {
            JsonNode response = postClassificationRequest(userMessage.trim(), history);
            return applyExplicitReplyMode(validate(parseResult(response)), explicitReplyMode);
        } catch (Exception e) {
            log.warn("意图模型调用失败，降级为普通聊天: reason={}", e.getMessage());
            return applyExplicitReplyMode(IntentResult.of(IntentType.CHAT, 0.5), explicitReplyMode);
        }
    }

    private IntentResult classifyByRule(String userMessage) {
        String normalized = userMessage.trim();
        String lowerCase = normalized.toLowerCase(Locale.ROOT);
        if (HELP_COMMANDS.contains(lowerCase)) {
            return IntentResult.of(IntentType.HELP, 1);
        }
        if (CLEAR_CONTEXT_PATTERN.matcher(normalized).matches()) {
            return IntentResult.of(IntentType.CLEAR_CONTEXT, 1);
        }

        Matcher weatherMatcher = STRONG_WEATHER_PATTERN.matcher(normalized);
        if (weatherMatcher.matches()) {
            String location = weatherMatcher.group(2).trim().replaceFirst("^的", "");
            String dateExpression = weatherMatcher.group(1) != null
                    ? weatherMatcher.group(1)
                    : weatherMatcher.group(3);
            if (!location.isBlank()) {
                return IntentResult.weather(1, location, normalizeWeatherDate(dateExpression));
            }
        }

        Matcher placeMatcher = STRONG_PLACE_SEARCH_PATTERN.matcher(normalized);
        if (placeMatcher.matches()) {
            String location = placeMatcher.group(1).trim().replaceFirst("(?:的)?附近$", "");
            String keyword = placeMatcher.group(2).trim().replaceFirst("^(?:的|附近的)", "");
            if (!location.isBlank() && !keyword.isBlank()) {
                return IntentResult.placeSearch(1, location, keyword);
            }
        }

        Matcher imageMatcher = STRONG_IMAGE_PATTERN.matcher(normalized);
        if (imageMatcher.matches() && !imageMatcher.group(1).isBlank()) {
            return IntentResult.image(1, imageMatcher.group(1));
        }
        return null;
    }

    private JsonNode postClassificationRequest(String userMessage, List<LlmMessage> history) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", llmProperties.getIntentModel());

        ObjectNode input = objectMapper.createObjectNode();
        ArrayNode messages = objectMapper.createArrayNode();
        messages.add(message("system", SYSTEM_PROMPT));

        List<LlmMessage> safeHistory = history == null ? List.of() : history;
        int start = Math.max(0, safeHistory.size() - MAX_HISTORY_MESSAGES);
        for (int i = start; i < safeHistory.size(); i++) {
            LlmMessage historyMessage = safeHistory.get(i);
            if (historyMessage != null && historyMessage.getRole() != null && historyMessage.getContent() != null) {
                messages.add(message(historyMessage.getRole(), historyMessage.getContent()));
            }
        }
        messages.add(message("user", userMessage));
        input.set("messages", messages);
        body.set("input", input);

        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("result_format", "message");
        parameters.put("temperature", 0);
        ObjectNode responseFormat = objectMapper.createObjectNode();
        responseFormat.put("type", "json_object");
        parameters.set("response_format", responseFormat);
        body.set("parameters", parameters);

        String json = objectMapper.writeValueAsString(body);
        Request request = new Request.Builder()
                .url(llmProperties.getBaseUrl() + "/services/aigc/text-generation/generation")
                .header("Authorization", "Bearer " + llmProperties.getApiKey())
                .header("Content-Type", "application/json")
                .post(RequestBody.create(json, MediaType.parse("application/json")))
                .build();

        try (Response response = okHttpClient.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IllegalStateException("意图模型API调用失败: status=" + response.code());
            }
            return objectMapper.readTree(responseBody);
        }
    }

    private ObjectNode message(String role, String content) {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private IntentResult parseResult(JsonNode response) throws Exception {
        JsonNode contentNode = response.path("output").path("choices").path(0).path("message").path("content");
        String content = contentNode.isTextual()
                ? contentNode.asText()
                : contentNode.path(0).path("text").asText();
        if (content.isBlank()) {
            content = response.path("output").path("text").asText();
        }
        if (content.isBlank()) {
            throw new IllegalStateException("意图模型未返回分类结果");
        }

        JsonNode resultNode = objectMapper.readTree(stripCodeFence(content));
        IntentType intent = parseIntent(resultNode.path("intent").asText());
        double confidence = resultNode.path("confidence").asDouble(0);
        Map<String, String> slots = new LinkedHashMap<>();
        JsonNode slotsNode = resultNode.path("slots");
        if (slotsNode.isObject()) {
            slotsNode.fields().forEachRemaining(entry -> {
                if (entry.getValue().isValueNode()) {
                    slots.put(entry.getKey(), entry.getValue().asText());
                }
            });
        }
        return new IntentResult(
                intent,
                confidence,
                slots,
                parseReplyMode(resultNode.path("replyMode").asText()),
                resultNode.path("needsClarification").asBoolean(false),
                resultNode.path("clarificationQuestion").asText("")
        );
    }

    private IntentResult validate(IntentResult result) {
        if (result.intent() == IntentType.WEATHER && result.slot("location").isBlank()) {
            return clarification(result, "请告诉我你要查询哪个城市或地区的天气。");
        }
        if (result.intent() == IntentType.PLACE_SEARCH && result.slot("location").isBlank()) {
            return clarification(result, "请告诉我你要在哪个城市或地区搜索地点。");
        }
        if (result.intent() == IntentType.PLACE_SEARCH && result.slot("keyword").isBlank()) {
            return clarification(result, "你想搜索景点、餐厅、医院，还是其他地点？");
        }
        if (result.intent() == IntentType.GENERATE_IMAGE && result.slot("prompt").isBlank()) {
            return clarification(result, "你希望我生成一张什么样的图片？");
        }
        if (result.intent() != IntentType.CHAT
                && result.intent() != IntentType.UNKNOWN
                && result.confidence() < ACTION_CONFIDENCE_THRESHOLD) {
            return clarification(result, "我还不确定你的意思，请再具体说明一下。");
        }
        if (result.intent() == IntentType.UNKNOWN && result.clarificationQuestion().isBlank()) {
            return clarification(result, "我还不确定你想让我做什么，请再具体说明一下。");
        }
        return result;
    }

    private String normalizeWeatherDate(String dateExpression) {
        if (dateExpression == null || dateExpression.isBlank()) {
            return "TODAY";
        }
        return switch (dateExpression) {
            case "明天" -> "TOMORROW";
            case "后天" -> "DAY_AFTER_TOMORROW";
            case "未来三天", "未来3天" -> "NEXT_3_DAYS";
            case "未来七天", "未来7天", "本周", "这周" -> "NEXT_7_DAYS";
            default -> "TODAY";
        };
    }

    private IntentResult clarification(IntentResult source, String question) {
        return new IntentResult(
                IntentType.UNKNOWN, 0, Map.of(), source.replyMode(), true, question
        );
    }

    private ReplyMode detectExplicitReplyMode(String message) {
        int voicePosition = lastMatchPosition(VOICE_REPLY_PATTERN, message);
        int textPosition = lastMatchPosition(TEXT_REPLY_PATTERN, message);
        if (voicePosition < 0 && textPosition < 0) {
            return ReplyMode.AUTO;
        }
        return textPosition > voicePosition ? ReplyMode.TEXT : ReplyMode.VOICE;
    }

    private int lastMatchPosition(Pattern pattern, String message) {
        Matcher matcher = pattern.matcher(message);
        int position = -1;
        while (matcher.find()) {
            position = matcher.start();
        }
        return position;
    }

    private IntentResult applyExplicitReplyMode(IntentResult result, ReplyMode explicitReplyMode) {
        if (explicitReplyMode == ReplyMode.AUTO) {
            return result;
        }
        return new IntentResult(
                result.intent(), result.confidence(), result.slots(), explicitReplyMode,
                result.needsClarification(), result.clarificationQuestion()
        );
    }

    private IntentType parseIntent(String value) {
        try {
            return IntentType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return IntentType.UNKNOWN;
        }
    }

    private ReplyMode parseReplyMode(String value) {
        try {
            return ReplyMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return ReplyMode.AUTO;
        }
    }

    private String stripCodeFence(String content) {
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```(?:json)?\\s*", "");
            trimmed = trimmed.replaceFirst("\\s*```$", "");
        }
        return trimmed;
    }
}
