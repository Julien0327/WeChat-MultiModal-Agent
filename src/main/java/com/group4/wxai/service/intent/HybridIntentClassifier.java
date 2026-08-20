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
    private static final Pattern EXPLICIT_HELP_PATTERN = Pattern.compile(
            "^(?:请|麻烦)?(?:告诉我|介绍一下)?(?:你|机器人|这个机器人|公众号|这个公众号)?"
                    + "(?:会做什么|能做什么|会什么|能干什么|支持什么|支持哪些功能|有哪些功能|有什么功能)"
                    + "(?:呢|吗|呀)?[？?]?$"
                    + "|^(?:请|麻烦)?(?:告诉我)?(?:你|机器人|这个机器人|公众号|这个公众号)?"
                    + "(?:怎么用|如何使用|使用方法|使用说明)(?:呢|吗)?[？?]?$"
    );
    private static final Pattern STRONG_IMAGE_PATTERN = Pattern.compile(
            "^(?:请|麻烦|可以|能不能)?\\s*(?:帮我)?\\s*"
                    + "(?:画(?!家|画)|绘制|生成(?:一张|一幅|一个|个|图片|图像)?|来一张|做一张|画图)\\s*(.+)$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern STRONG_WEATHER_PATTERN = Pattern.compile(
            "^(?:请|麻烦)?(?:帮我)?(?:查(?:一下)?|查询|看看|看一下)?\\s*"
                    + "(?:(今天|明天|后天|(?:未来|最近|近)(?:几|三|3|七|7)天|本周|这周)\\s*(?:的)?\\s*)?"
                    + "(.{2,40}?)"
                    + "(?:(今天|明天|后天|(?:未来|最近|近)(?:几|三|3|七|7)天|本周|这周))?"
                    + "(?:的)?(?:天气|气温|温度)"
                    + "(?:怎么样|如何|预报|情况|是多少|多少度)?[？?]?$"
    );
    private static final Pattern STRONG_PLACE_SEARCH_PATTERN = Pattern.compile(
            "^(?:请|麻烦)?(?:帮我)?(?:查(?:一下)?|查询|搜索|找(?:一下)?|看看)?\\s*"
                    + "(.{2,30}?)(?:附近)?(?:有(?:什么|哪些)?|哪里有)\\s*"
                    + "(.{1,40}?)(?:推荐)?[吗呢？?]*$"
    );
    private static final Pattern STRONG_WORLD_CLOCK_PATTERN = Pattern.compile(
            "^(?:请|麻烦)?(?:帮我)?(?:查(?:一下)?|查询|看看|看一下)?\\s*(?:现在)?\\s*"
                    + "(.{1,80}?)(?:现在|当地)?(?:的)?(?:是)?(?:几点(?:钟)?(?:了)?|时间)"
                    + "(?:是多少)?[？?]?$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern DEFAULT_WORLD_CLOCK_PATTERN = Pattern.compile(
            "^(?:请|麻烦)?(?:帮我)?(?:查(?:一下)?|查询|看看|看一下)?\\s*"
                    + "(?:现在|当前)?(?:的)?(?:是)?(?:几点(?:钟)?(?:了)?|时间)"
                    + "(?:是多少)?[？?]?$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern STRONG_WEB_SEARCH_PATTERN = Pattern.compile(
            "^(?:请|麻烦)?(?:帮我)?(?:联网|上网)?(?:搜索|搜一下|查一下|查询一下)\\s*(.{2,300})[？?]?$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PREFIX_DEFINITION_SEARCH_PATTERN = Pattern.compile(
            "^(?:请|麻烦)?(?:告诉我|解释一下)?\\s*什么是\\s*(.{2,120}?)[？?]?$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SUFFIX_DEFINITION_SEARCH_PATTERN = Pattern.compile(
            "^(?:请|麻烦)?(?:告诉我|解释一下)?\\s*(.{2,120}?)\\s*(?:是什么|是什么意思)[？?]?$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Set<String> CONTEXTUAL_DEFINITION_SUBJECTS = Set.of(
            "你", "我", "他", "她", "它", "这", "这个", "那", "那个", "这些", "那些"
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
            - WEB_SEARCH：用户要求联网搜索实时信息、新闻、官网、近期变化或明确说“上网查”“联网搜索”。
            - WORLD_CLOCK：查询某个城市或时区当前几点、当地日期和时间。
            - TRIP_PLAN：用户明确要求规划旅行、出游或行程。只询问天气、地点或普通聊天时不能使用。
            - GENERATE_IMAGE：用户明确要求创建、绘制或生成新图片。询问画家、绘画知识不属于该意图。
            - CLEAR_CONTEXT：用户明确要求清除、重置或忘记当前对话历史。
            - HELP：用户询问机器人会什么、如何使用或请求功能说明。
            - UNKNOWN：消息不完整或仅凭上下文仍无法判断用户希望做什么。

            必须输出以下 JSON 结构：
            {"intent":"CHAT","confidence":0.0,"slots":{},"replyMode":"AUTO","needsClarification":false,"clarificationQuestion":""}

            GENERATE_IMAGE 时从用户原话提取纯图片描述，放入 slots.prompt；不要自行补充用户未表达的主题。
            WEATHER 时必须提取 slots.location，并将日期写入 slots.date。date 只使用 TODAY、TOMORROW、
            DAY_AFTER_TOMORROW、NEXT_3_DAYS、NEXT_7_DAYS 或 YYYY-MM-DD；未指定日期时使用 TODAY。
            location 必须来自用户当前消息明确说出的城市或地区。当前消息只说“今天天气怎么样”时，
            location 必须是空字符串；禁止从历史对话、上次查询地点、IP 或常识中补全地点。
            当前消息是在回答机器人刚才明确提出的地点追问时，才可以结合紧邻上下文提取该回答中的地点。
            “最近几天”“最近三天”“近三天”按未来三天处理；“最近七天”“近七天”按未来七天处理。
            PLACE_SEARCH 时提取 slots.location 和 slots.keyword。例如“绵阳有哪些景点”应输出
            location=绵阳、keyword=景点；“江油哪里有医院”应输出 location=江油、keyword=医院。
            location 和 keyword 都必须是当前消息明确出现的短语；“有没有近点的”“今晚想回来睡觉”等
            行程偏好不是地点搜索，禁止把“有没”“近点的啊”等句子碎片填入地点字段。
            WEB_SEARCH 时将完整但精简的搜索问题写入 slots.query，不要把“帮我搜索”“联网查一下”等前缀放入 query。
            WORLD_CLOCK 时提取 slots.location，例如“东京现在几点”应输出 location=东京，
            “UTC+5:30 是几点”应输出 location=UTC+5:30。未给城市或时区时默认 location=北京。
            TRIP_PLAN 的 slots 必须始终严格使用下面的 JSON Schema；五个字段一个都不能省略，
            用户没说的值必须写空字符串，禁止根据历史地点、IP、常识或模型判断自行猜测：
            {"type":"object","properties":{"origin":{"type":"string"},"destination":{"type":"string"},
            "departure_time":{"type":"string"},"duration":{"type":"string"},"preference":{"type":"string"}},
            "required":["origin","destination","departure_time","duration","preference"],"additionalProperties":false}
            origin 是出发城市或地区；destination 是用户确认的目的城市或地区；departure_time 是出行日期
            和可选时刻；duration 是游玩时长；preference 是景观、距离、同行人、预算、节奏等偏好。
            用户明确说没想好目的地并要求推荐时，destination 写 RECOMMEND。
            destination 只能填写提取后的纯地点名称，不能复制整句话。例如“我去三亚吧”必须填写“三亚”，
            “那就选海口东线”应填写“海口东线”；不能填写“我去三亚吧”或“那就选海口东线”。
            “雪山”“海边”“草原”“古镇”“避暑”“亲子”等是偏好，不是明确目的地，应放入 preference；
            “玉龙雪山”“青岛”“呼伦贝尔”这类专名才可以放入 destination。例如“明天从江油出发玩7天，
            想看雪山，目的地你推荐”应输出 origin=江油、destination=RECOMMEND、departure_time=明天、
            duration=7天、preference=雪山。当前消息没有偏好时 preference 必须为空字符串。
            信息不完整时仍输出 TRIP_PLAN、保留已知字段，并令 needsClarification=false；外部程序负责逐项询问，
            在四项齐全前不会调用任何出行工具。只有用户明确要求规划行程时才能使用 TRIP_PLAN。
            replyMode 只允许 AUTO、TEXT、VOICE。用户明确要求“用语音回答”时使用 VOICE，
            明确要求“用文字回答”时使用 TEXT，否则使用 AUTO。回复形式不是业务意图。
            只有确实无法判断时才使用 UNKNOWN，并提供简短 clarificationQuestion。
            HELP 只用于询问机器人能力、功能列表或使用方法。“你怎么获取这个信息的”“你怎么知道时间”
            是在询问信息来源或工作原理，必须分类为 CHAT，不能分类为 HELP。
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
            return applyExplicitReplyMode(validate(parseResult(response), userMessage), explicitReplyMode);
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
            if (isWeatherDateExpression(location) && dateExpression == null) {
                return IntentResult.weather(1, "", normalizeWeatherDate(location));
            }
            if (!location.isBlank()) {
                return IntentResult.weather(1, location, normalizeWeatherDate(dateExpression));
            }
        }

        Matcher placeMatcher = STRONG_PLACE_SEARCH_PATTERN.matcher(normalized);
        if (placeMatcher.matches()) {
            String location = placeMatcher.group(1).trim().replaceFirst("(?:的)?附近$", "");
            String keyword = placeMatcher.group(2).trim().replaceFirst("^(?:的|附近的)", "");
            if (isPlausiblePlaceLocation(location) && isPlausiblePlaceKeyword(keyword)) {
                return IntentResult.placeSearch(1, location, keyword);
            }
        }

        if (DEFAULT_WORLD_CLOCK_PATTERN.matcher(normalized).matches()) {
            return IntentResult.worldClock(1, "北京");
        }
        Matcher worldClockMatcher = STRONG_WORLD_CLOCK_PATTERN.matcher(normalized);
        if (worldClockMatcher.matches() && !worldClockMatcher.group(1).isBlank()) {
            return IntentResult.worldClock(1, worldClockMatcher.group(1));
        }

        Matcher webSearchMatcher = STRONG_WEB_SEARCH_PATTERN.matcher(normalized);
        if (webSearchMatcher.matches() && !webSearchMatcher.group(1).isBlank()) {
            return IntentResult.webSearch(1, webSearchMatcher.group(1));
        }

        String definitionSubject = extractDefinitionSubject(normalized);
        if (definitionSubject != null) {
            return IntentResult.webSearch(1, definitionSubject + " 是什么");
        }

        Matcher imageMatcher = STRONG_IMAGE_PATTERN.matcher(normalized);
        if (imageMatcher.matches() && !imageMatcher.group(1).isBlank()) {
            return IntentResult.image(1, imageMatcher.group(1));
        }
        return null;
    }

    private String extractDefinitionSubject(String message) {
        Matcher prefixMatcher = PREFIX_DEFINITION_SEARCH_PATTERN.matcher(message);
        if (prefixMatcher.matches()) {
            return normalizeDefinitionSubject(prefixMatcher.group(1));
        }
        Matcher suffixMatcher = SUFFIX_DEFINITION_SEARCH_PATTERN.matcher(message);
        if (suffixMatcher.matches()) {
            return normalizeDefinitionSubject(suffixMatcher.group(1));
        }
        return null;
    }

    private String normalizeDefinitionSubject(String value) {
        if (value == null) {
            return null;
        }
        String subject = value.trim();
        if (subject.isBlank() || CONTEXTUAL_DEFINITION_SUBJECTS.contains(subject)
                || subject.startsWith("你觉得") || subject.startsWith("你认为")) {
            return null;
        }
        return subject;
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

    private IntentResult validate(IntentResult result, String userMessage) {
        if (result.intent() == IntentType.HELP && !isExplicitHelpRequest(userMessage)) {
            return IntentResult.of(IntentType.CHAT, Math.max(0.8, result.confidence()));
        }
        if (result.intent() == IntentType.WEATHER) {
            String location = result.slot("location");
            if (!isExplicitLocationInCurrentMessage(userMessage, location)) {
                location = "";
            }
            IntentResult normalized = IntentResult.weather(
                    result.confidence(), location, result.slot("date")
            );
            result = new IntentResult(
                    normalized.intent(), normalized.confidence(), normalized.slots(),
                    result.replyMode(), false, ""
            );
        }
        if (result.intent() == IntentType.PLACE_SEARCH && result.slot("location").isBlank()) {
            return clarification(result, "请告诉我你要在哪个城市或地区搜索地点。");
        }
        if (result.intent() == IntentType.PLACE_SEARCH && result.slot("keyword").isBlank()) {
            return clarification(result, "你想搜索景点、餐厅、医院，还是其他地点？");
        }
        if (result.intent() == IntentType.PLACE_SEARCH
                && (!isExplicitLocationInCurrentMessage(userMessage, result.slot("location"))
                || !isPlausiblePlaceLocation(result.slot("location"))
                || !isPlausiblePlaceKeyword(result.slot("keyword")))) {
            return IntentResult.of(IntentType.CHAT, Math.max(0.8, result.confidence()));
        }
        if (result.intent() == IntentType.WEB_SEARCH && result.slot("query").isBlank()) {
            return clarification(result, "请告诉我要联网搜索什么内容。");
        }
        if (result.intent() == IntentType.WORLD_CLOCK && result.slot("location").isBlank()) {
            return new IntentResult(
                    IntentType.WORLD_CLOCK, result.confidence(), Map.of("location", "北京"),
                    result.replyMode(), false, ""
            );
        }
        if (result.intent() == IntentType.TRIP_PLAN) {
            IntentResult normalized = IntentResult.tripPlan(
                    result.confidence(),
                    result.slot("origin"),
                    result.slot("destination"),
                    result.slot("departure_time"),
                    result.slot("duration"),
                    result.slot("preference")
            );
            result = new IntentResult(
                    normalized.intent(), normalized.confidence(), normalized.slots(), result.replyMode(), false, ""
            );
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

    private boolean isExplicitHelpRequest(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalized = message.trim();
        return HELP_COMMANDS.contains(normalized.toLowerCase(Locale.ROOT))
                || EXPLICIT_HELP_PATTERN.matcher(normalized).matches();
    }

    private String normalizeWeatherDate(String dateExpression) {
        if (dateExpression == null || dateExpression.isBlank()) {
            return "TODAY";
        }
        return switch (dateExpression) {
            case "明天" -> "TOMORROW";
            case "后天" -> "DAY_AFTER_TOMORROW";
            case "未来几天", "最近几天", "近几天", "未来三天", "未来3天", "最近三天", "最近3天", "近三天", "近3天" -> "NEXT_3_DAYS";
            case "未来七天", "未来7天", "最近七天", "最近7天", "近七天", "近7天", "本周", "这周" -> "NEXT_7_DAYS";
            default -> "TODAY";
        };
    }

    private boolean isWeatherDateExpression(String value) {
        if (value == null) {
            return false;
        }
        return Set.of(
                "今天", "明天", "后天", "未来几天", "最近几天", "近几天",
                "未来三天", "未来3天", "最近三天", "最近3天", "近三天", "近3天",
                "未来七天", "未来7天", "最近七天", "最近7天", "近七天", "近7天", "本周", "这周"
        ).contains(value.trim());
    }

    private boolean isExplicitLocationInCurrentMessage(String message, String location) {
        if (message == null || location == null || location.isBlank() || isWeatherDateExpression(location)) {
            return false;
        }
        String normalizedMessage = message.replaceAll("\\s+", "");
        String normalizedLocation = location.replaceAll("\\s+", "")
                .replaceFirst("(?:特别行政区|自治区|自治州|省|市|区|县)$", "");
        return !normalizedLocation.isBlank() && normalizedMessage.contains(normalizedLocation);
    }

    private boolean isPlausiblePlaceLocation(String value) {
        if (value == null || value.isBlank() || value.length() > 30) {
            return false;
        }
        String normalized = value.trim();
        return !normalized.matches(".*[，,。；;！!？?].*")
                && !normalized.matches(".*(?:有没有|有没|哪里|什么|哪些|近点|附近|回来|睡觉|"
                + "今天|明天|晚上|现在|可以|想去|出去).*");
    }

    private boolean isPlausiblePlaceKeyword(String value) {
        if (value == null || value.isBlank() || value.length() > 40) {
            return false;
        }
        String normalized = value.trim();
        return !normalized.matches(".*[，,。；;！!？?].*")
                && !normalized.matches(".*(?:有没有|有没|回来|睡觉|今天|明天|晚上|现在|"
                + "近点|近一点|可以去哪里).*");
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
