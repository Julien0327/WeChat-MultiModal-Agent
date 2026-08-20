package com.group4.wxai.service;

import com.group4.wxai.model.IntentResult;
import com.group4.wxai.model.IntentType;
import com.group4.wxai.model.TripPlanDraft;
import com.group4.wxai.model.TripPlanRequest;
import com.group4.wxai.service.llm.LlmService;
import com.group4.wxai.service.tool.PlaceSearchTool;
import com.group4.wxai.service.tool.ToolRegistry;
import com.group4.wxai.service.tool.TripPlanningTool;
import com.group4.wxai.service.tool.WebSearchTool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TripPlanningService {

    private static final ZoneId BEIJING_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Pattern ISO_DATE = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern MONTH_DAY = Pattern.compile("(\\d{1,2})月(\\d{1,2})日?");
    private static final Pattern WEEKDAY = Pattern.compile("(下周|本周|这周|周|星期|礼拜)([一二三四五六日天])");
    private static final Pattern VALID_DURATION = Pattern.compile(
            "^(?:大约|约)?(?:半天|[一二两三四五六七八九十百\\d]+(?:个)?(?:小时|天|日|晚上|晚|周|星期))$"
    );
    private static final Pattern DURATION_IN_TEXT = Pattern.compile(
            "(半天|[一二两三四五六七八九十百\\d]+(?:个)?(?:小时|天|日|晚上|晚|周|星期))"
    );
    private static final Pattern LEADING_TIME_OF_DAY = Pattern.compile(
            "^\\s*(?:的)?\\s*((?:凌晨|早上|上午|中午|下午|傍晚|晚上|晚间)"
                    + "(?:\\s*\\d{1,2}(?:[:：]\\d{2}|点(?:半|\\d{1,2}分?)?)?)?"
                    + "|\\d{1,2}(?:[:：]\\d{2}|点(?:半|\\d{1,2}分?)?))"
    );
    private static final Pattern LOCATION_QUESTION_OR_COMMAND = Pattern.compile(
            ".*(?:[？?]|吗|呢|天气|几点|时间|为什么|怎么|如何|搜索|查询|查一下|画|图片|生成|语音|聊天).*"
    );
    private static final Pattern RECOMMEND_DESTINATION = Pattern.compile(
            ".*(?:没想好|还没想好|不知道去哪|不知道去哪里|帮我推荐|你推荐|推荐一个|推荐目的地|"
                    + "可以去哪里|去哪玩|去哪里玩|想出去玩).*"
    );
    private static final Pattern CANCEL = Pattern.compile(
            "^(?:算了|不用了|取消|不规划了|先不规划了|停止规划)[。！! ]*$"
    );
    private static final Pattern ORIGIN_IN_TEXT = Pattern.compile(
            "(?:我)?(?:现在)?(?:在|从)"
                    + "([^，,。；;！!？?]{2,30}?)(?=(?:就)?(?:现在|今天|明天|后天|马上|立刻)?"
                    + "(?:出发|走|动身)|[，,。；;！!？?]|$)"
    );
    private static final Pattern IMMEDIATE_DEPARTURE = Pattern.compile(
            ".*(?:现在|马上|立刻)(?:就)?(?:出发|走|动身).*"
    );
    private static final Pattern RECOMMENDATION_REFINEMENT = Pattern.compile(
            ".*(?:近点|近一点|附近|周边|太远|远了|当天回来|当晚回来|晚上回来|回来睡觉|"
                    + "不住宿|不住店|往返|来回).*"
    );
    private static final List<PreferenceRule> PREFERENCE_RULES = List.of(
            new PreferenceRule(Pattern.compile(".*(?:海边|沿海|看海|沙滩|海岛|海滨).*"), "海边/沿海"),
            new PreferenceRule(Pattern.compile(".*(?:雪山|冰川|看雪|赏雪).*"), "雪山"),
            new PreferenceRule(Pattern.compile(".*(?:远点|远一点|远一些|远一点儿|去远处|可以去远).*"), "可以远途"),
            new PreferenceRule(Pattern.compile(".*(?:近点|近一点|近一些|附近|周边).*"), "距离较近"),
            new PreferenceRule(Pattern.compile(".*(?:当天回来|当晚回来|晚上回来|当天往返|来回|往返).*"), "当天往返"),
            new PreferenceRule(Pattern.compile(".*(?:不住宿|不住店|不住酒店|回来睡觉).*"), "不住宿"),
            new PreferenceRule(Pattern.compile(".*(?:亲子|带孩子|带小孩|小朋友).*"), "亲子"),
            new PreferenceRule(Pattern.compile(".*(?:老人|父母|长辈).*"), "适合老人"),
            new PreferenceRule(Pattern.compile(".*(?:避暑|凉快|清凉).*"), "避暑"),
            new PreferenceRule(Pattern.compile(".*(?:美食|好吃|吃东西|小吃).*"), "美食"),
            new PreferenceRule(Pattern.compile(".*(?:古镇|古城|历史|人文|博物馆).*"), "人文历史"),
            new PreferenceRule(Pattern.compile(".*(?:自然风景|山水|森林|草原|爬山).*"), "自然风景"),
            new PreferenceRule(Pattern.compile(".*(?:轻松|少走路|不爬山|不要太累).*"), "轻松少走路"),
            new PreferenceRule(Pattern.compile(".*(?:省钱|便宜|低预算|预算有限|性价比).*"), "预算友好"),
            new PreferenceRule(Pattern.compile(".*(?:自驾|开车去).*"), "自驾")
    );
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Pattern GENERIC_DESTINATION_PREFERENCE = Pattern.compile(
            "^(?:雪山|冰川|海边|沿海|海岛|沙滩|海滨|草原|森林|山里|山区|湖边|古镇|古城|"
                    + "温泉|避暑|亲子|美食|自然风景|人文景点)$"
    );

    private final PlaceSearchTool placeSearchTool;
    private final WebSearchTool webSearchTool;
    private final ToolRegistry toolRegistry;
    private final LlmService llmService;
    private final Clock clock;
    private final Map<String, TripPlanDraft> drafts = new ConcurrentHashMap<>();

    @Autowired
    public TripPlanningService(PlaceSearchTool placeSearchTool, WebSearchTool webSearchTool,
                               ToolRegistry toolRegistry, LlmService llmService) {
        this(placeSearchTool, webSearchTool, toolRegistry, llmService, Clock.system(BEIJING_ZONE));
    }

    TripPlanningService(PlaceSearchTool placeSearchTool, WebSearchTool webSearchTool,
                        ToolRegistry toolRegistry, LlmService llmService, Clock clock) {
        this.placeSearchTool = placeSearchTool;
        this.webSearchTool = webSearchTool;
        this.toolRegistry = toolRegistry;
        this.llmService = llmService;
        this.clock = clock;
    }

    public boolean hasPendingPlan(String userId) {
        return drafts.containsKey(userId);
    }

    public boolean shouldHandlePendingAnswer(String userId, String message, IntentResult classifiedIntent) {
        TripPlanDraft draft = drafts.get(userId);
        if (draft == null || message == null || message.isBlank()) {
            return false;
        }
        if (CANCEL.matcher(message.trim()).matches() || classifiedIntent.intent() == IntentType.TRIP_PLAN) {
            return true;
        }
        if (RECOMMENDATION_REFINEMENT.matcher(message.trim()).matches()) {
            return true;
        }
        if (!extractPreference(message).isBlank()) {
            return true;
        }
        if (isSafeDirectAnswer(draft.nextMissingField(), message)) {
            return true;
        }
        if (classifiedIntent.intent() != IntentType.CHAT && classifiedIntent.intent() != IntentType.UNKNOWN) {
            return false;
        }
        return false;
    }

    public TripPlanTurn handleTurn(String userId, String message, IntentResult intent) {
        String normalizedMessage = message == null ? "" : message.trim();
        if (CANCEL.matcher(normalizedMessage).matches()) {
            drafts.remove(userId);
            return new TripPlanTurn("好，已经取消这次出行规划。", false);
        }

        boolean continuingDraft = drafts.containsKey(userId);
        TripPlanDraft draft = drafts.getOrDefault(userId, TripPlanDraft.empty());
        String awaitedField = draft.nextMissingField();
        String messagePreference = extractPreference(normalizedMessage);
        if (intent != null && intent.intent() == IntentType.TRIP_PLAN) {
            if (!continuingDraft) {
                draft = draft.merge(intent.slots()).with("preference", "");
                draft = draft.with("destination", resolveDestinationAnswer(normalizedMessage, intent));
            }
        }
        draft = applyExplicitTripDetails(draft, normalizedMessage);
        String effectivePreference = mergePreference(
                messagePreference, resolveModelPreference(normalizedMessage, intent)
        );
        effectivePreference = mergePreference(effectivePreference, destinationAsPreference(intent));
        if (!effectivePreference.isBlank()) {
            draft = draft.with("preference", mergePreference(draft.preference(), effectivePreference));
        }
        if (continuingDraft && "destination".equals(awaitedField)) {
            String extractedDestination = resolveDestinationAnswer(normalizedMessage, intent);
            if (!extractedDestination.isBlank()) {
                draft = draft.with("destination", extractedDestination);
            }
        }
        if (continuingDraft && "destination".equals(awaitedField)
                && (RECOMMENDATION_REFINEMENT.matcher(normalizedMessage).matches()
                || !effectivePreference.isBlank())) {
            draft = normalizeAndValidateDraft(draft);
            if (!draft.origin().isBlank() && !draft.departureTime().isBlank() && !draft.duration().isBlank()) {
                return recommendDestination(userId,
                        draft.with("destination", TripPlanRequest.RECOMMEND_DESTINATION));
            }
        }
        if (continuingDraft && !awaitedField.isBlank() && !"destination".equals(awaitedField)
                && isSafeDirectAnswer(awaitedField, normalizedMessage)) {
            draft = applyDirectAnswer(draft, awaitedField, normalizedMessage);
        }
        draft = normalizeAndValidateDraft(draft);

        String missing = draft.nextMissingField();
        if (!missing.isBlank()) {
            drafts.put(userId, draft);
            return new TripPlanTurn(questionFor(missing), false);
        }

        if (draft.toRequest().needsDestinationRecommendation()) {
            return recommendDestination(userId, draft);
        }

        String reply = toolRegistry.execute(TripPlanningTool.NAME, draft.toRequest().toArguments());
        drafts.remove(userId);
        return new TripPlanTurn(reply, true);
    }

    public void clear(String userId) {
        drafts.remove(userId);
    }

    public Optional<TripPlanDraft> pendingDraft(String userId) {
        return Optional.ofNullable(drafts.get(userId));
    }

    private TripPlanTurn recommendDestination(String userId, TripPlanDraft draft) {
        TripPlanRequest request = draft.toRequest();
        String preference = draft.preference();
        String amapResults = placeSearchTool.execute(Map.of(
                "location", request.origin(),
                "keyword", preferenceKeyword(preference)
        ));
        String webResults = webSearchTool.execute(Map.of(
                "query", "从" + request.origin() + "出发 " + request.departureTime() + " "
                        + request.duration() + " " + recommendationScope(preference) + "目的地推荐 最新攻略"
                        + (preference.isBlank() ? "" : " 偏好：" + preference)
        ));
        String prompt = """
                用户还没有决定目的地。请只依据下面的高德和联网结果，给出 3 至 5 个候选目的地或景点，
                简要说明各自适合的玩法和信息依据。不要编造车程、票价、营业时间，也不要擅自替用户选定。
                最后明确询问用户选择哪一个；用户确认后程序才会继续查天气并制定完整行程。

                出发地：%s
                出行时间：%s
                出行时长：%s
                用户补充要求：%s

                高德结果：
                %s

                联网结果：
                %s
                """.formatted(
                request.origin(), request.departureTime(), request.duration(),
                preference.isBlank() ? "无" : preference,
                amapResults, webResults
        );
        String reply = llmService.chatText("trip-destination-recommendation", prompt, List.of());
        drafts.put(userId, new TripPlanDraft(
                draft.origin(), "", draft.departureTime(), draft.duration(), draft.preference()
        ));
        return new TripPlanTurn(reply, false);
    }

    private TripPlanDraft normalizeAndValidateDraft(TripPlanDraft draft) {
        String origin = normalizeLocation(draft.origin(), "origin");
        String destination = normalizeDestination(draft.destination());
        String departureTime = normalizeDepartureTime(draft.departureTime());
        String duration = normalizeDuration(draft.duration());
        String preference = normalizePreference(draft.preference());
        return new TripPlanDraft(origin, destination, departureTime, duration, preference);
    }

    private String extractPreference(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        Set<String> preferences = new LinkedHashSet<>();
        String normalized = message.trim();
        for (PreferenceRule rule : PREFERENCE_RULES) {
            if (rule.pattern().matcher(normalized).matches()) {
                preferences.add(rule.label());
            }
        }
        return String.join("、", preferences);
    }

    private String mergePreference(String existing, String incoming) {
        Set<String> preferences = new LinkedHashSet<>();
        addPreferences(preferences, normalizePreference(existing));
        String normalizedIncoming = normalizePreference(incoming);
        if (normalizedIncoming.contains("可以远途")) {
            preferences.remove("距离较近");
        } else if (normalizedIncoming.contains("距离较近")) {
            preferences.remove("可以远途");
        }
        addPreferences(preferences, normalizedIncoming);
        return String.join("、", preferences);
    }

    private void addPreferences(Set<String> preferences, String value) {
        if (!value.isBlank()) {
            for (String item : value.split("、")) {
                if (!item.isBlank()) {
                    preferences.add(item.trim());
                }
            }
        }
    }

    private String normalizePreference(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replaceAll("[\\r\\n\\t]", " ").trim();
        return normalized.length() > 120 ? normalized.substring(0, 120) : normalized;
    }

    private String preferenceKeyword(String preference) {
        if (preference.contains("海边/沿海")) {
            return "海边景点";
        }
        if (preference.contains("雪山")) {
            return "雪山景点";
        }
        if (preference.contains("亲子")) {
            return "亲子景点";
        }
        if (preference.contains("美食")) {
            return "特色美食";
        }
        if (preference.contains("人文历史")) {
            return "人文古迹";
        }
        return "旅游景点";
    }

    private String recommendationScope(String preference) {
        return preference.isBlank() || preference.contains("距离较近") ? "周边游" : "旅行";
    }

    private TripPlanDraft applyDirectAnswer(TripPlanDraft draft, String field, String message) {
        TripPlanDraft updated = draft.with(field, normalizeDirectAnswer(field, message));
        if ("departure_time".equals(field) && updated.duration().isBlank()) {
            String duration = normalizeDuration(message);
            if (!duration.isBlank()) {
                updated = updated.with("duration", duration);
            }
        } else if ("duration".equals(field) && updated.departureTime().isBlank()) {
            String departureTime = normalizeDepartureTime(message);
            if (!departureTime.isBlank()) {
                updated = updated.with("departure_time", departureTime);
            }
        }
        return updated;
    }

    private TripPlanDraft applyExplicitTripDetails(TripPlanDraft draft, String message) {
        TripPlanDraft updated = draft;
        String origin = extractOrigin(message);
        if (!origin.isBlank()) {
            updated = updated.with("origin", origin);
        }
        String departureTime = normalizeDepartureTime(message);
        if (!departureTime.isBlank()) {
            updated = updated.with("departure_time", departureTime);
        }
        String duration = normalizeDuration(message);
        if (!duration.isBlank()) {
            updated = updated.with("duration", duration);
        }
        return updated;
    }

    private String extractOrigin(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        Matcher matcher = ORIGIN_IN_TEXT.matcher(message.trim());
        if (!matcher.find()) {
            return "";
        }
        String candidate = matcher.group(1).replaceFirst("[啊呀呢吧啦]+$", "").trim();
        return isSafeLocation(candidate) ? candidate : "";
    }

    private String normalizeDirectAnswer(String field, String message) {
        String value = message.trim().replaceAll("[。！!]+$", "");
        return switch (field) {
            case "origin" -> value.replaceFirst("^(?:我)?(?:从|出发地(?:是|在)?)", "")
                    .replaceFirst("(?:出发)$", "").trim();
            case "destination" -> value.replaceFirst("^(?:我想)?(?:去|目的地(?:是|在)?)", "")
                    .replaceFirst("(?:玩|旅游|旅行)$", "").trim();
            case "duration" -> value.replaceFirst("^(?:玩|去|大概|准备|计划)", "").trim();
            default -> value;
        };
    }

    private String normalizeLocation(String value, String field) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = normalizeDirectAnswer(field, value);
        return isSafeLocation(normalized) ? normalized : "";
    }

    private String normalizeDestination(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (TripPlanRequest.RECOMMEND_DESTINATION.equalsIgnoreCase(value)
                || RECOMMEND_DESTINATION.matcher(value.trim()).matches()) {
            return TripPlanRequest.RECOMMEND_DESTINATION;
        }
        String destination = normalizeLocation(value, "destination");
        return isCleanDestinationCandidate(destination) ? destination : "";
    }

    private String resolveDestinationAnswer(String message, IntentResult intent) {
        String modelDestination = intent != null && intent.intent() == IntentType.TRIP_PLAN
                ? normalizeDestination(intent.slot("destination")) : "";
        if (TripPlanRequest.RECOMMEND_DESTINATION.equals(modelDestination)) {
            return RECOMMEND_DESTINATION.matcher(message).matches() ? modelDestination : "";
        }
        if (!modelDestination.isBlank() && isExplicitlyPresent(modelDestination, message)) {
            return modelDestination;
        }

        String bareAnswer = message == null ? "" : message.trim().replaceAll("[。！!]$", "");
        if (bareAnswer.equals(normalizeDirectAnswer("destination", bareAnswer))
                && isSafeLocation(bareAnswer) && isCleanDestinationCandidate(bareAnswer)) {
            return bareAnswer;
        }
        return "";
    }

    private String resolveModelPreference(String message, IntentResult intent) {
        if (intent == null || intent.intent() != IntentType.TRIP_PLAN) {
            return "";
        }
        String preference = normalizePreference(intent.slot("preference"));
        if (preference.isBlank()) {
            return "";
        }
        String normalizedMessage = message == null ? "" : message.replaceAll("\\s+", "");
        Set<String> accepted = new LinkedHashSet<>();
        for (String item : preference.split("[、,，/]")) {
            String normalizedItem = item.trim().replaceAll("\\s+", "");
            if (!normalizedItem.isBlank() && normalizedMessage.contains(normalizedItem)) {
                accepted.add(item.trim());
            }
        }
        return String.join("、", accepted);
    }

    private String destinationAsPreference(IntentResult intent) {
        if (intent == null || intent.intent() != IntentType.TRIP_PLAN) {
            return "";
        }
        String destination = intent.slot("destination").trim();
        return GENERIC_DESTINATION_PREFERENCE.matcher(destination).matches()
                ? extractPreference(destination) : "";
    }

    private boolean isExplicitlyPresent(String destination, String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalizedMessage = message.replaceAll("\\s+", "");
        String normalizedDestination = destination.replaceAll("\\s+", "");
        if (normalizedMessage.contains(normalizedDestination)) {
            return true;
        }
        String withoutAdministrativeSuffix = normalizedDestination.replaceFirst("(?:市|区|县)$", "");
        return withoutAdministrativeSuffix.length() >= 2
                && normalizedMessage.contains(withoutAdministrativeSuffix);
    }

    private boolean isCleanDestinationCandidate(String value) {
        return value != null && !value.isBlank() && value.length() <= 40
                && !GENERIC_DESTINATION_PREFERENCE.matcher(value).matches()
                && !value.matches(".*(?:我去|我要|我想|想去|要去|打算|准备|帮我|推荐|"
                + "吧|呀|呢|啊|旅游|旅行|游玩|可以去哪里).*" );
    }

    private String normalizeDepartureTime(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.trim();
        if ("现在".equals(normalized) || IMMEDIATE_DEPARTURE.matcher(normalized).matches()) {
            return ZonedDateTime.now(clock.withZone(BEIJING_ZONE)).format(DATE_TIME);
        }
        LocalDate today = LocalDate.now(clock.withZone(BEIJING_ZONE));
        Matcher isoMatcher = ISO_DATE.matcher(normalized);
        if (isoMatcher.find()) {
            try {
                LocalDate date = LocalDate.parse(isoMatcher.group(1));
                return formatDepartureTime(date, normalized.substring(isoMatcher.end()));
            } catch (Exception ignored) {
                return "";
            }
        }
        if (normalized.contains("今天")) {
            int end = normalized.indexOf("今天") + "今天".length();
            return formatDepartureTime(today, normalized.substring(end));
        }
        if (normalized.contains("明天")) {
            int end = normalized.indexOf("明天") + "明天".length();
            return formatDepartureTime(today.plusDays(1), normalized.substring(end));
        }
        if (normalized.contains("后天")) {
            int end = normalized.indexOf("后天") + "后天".length();
            return formatDepartureTime(today.plusDays(2), normalized.substring(end));
        }
        Matcher monthDayMatcher = MONTH_DAY.matcher(normalized);
        if (monthDayMatcher.find()) {
            try {
                LocalDate date = LocalDate.of(today.getYear(),
                        Integer.parseInt(monthDayMatcher.group(1)), Integer.parseInt(monthDayMatcher.group(2)));
                if (date.isBefore(today)) {
                    date = date.plusYears(1);
                }
                return formatDepartureTime(date, normalized.substring(monthDayMatcher.end()));
            } catch (Exception ignored) {
                return "";
            }
        }
        Matcher weekdayMatcher = WEEKDAY.matcher(normalized);
        if (weekdayMatcher.find()) {
            DayOfWeek dayOfWeek = parseDayOfWeek(weekdayMatcher.group(2));
            LocalDate date = today.with(TemporalAdjusters.nextOrSame(dayOfWeek));
            if ("下周".equals(weekdayMatcher.group(1))) {
                LocalDate nextMonday = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
                date = nextMonday.with(TemporalAdjusters.nextOrSame(dayOfWeek));
            }
            return formatDepartureTime(date, normalized.substring(weekdayMatcher.end()));
        }
        return "";
    }

    private String formatDepartureTime(LocalDate date, String textAfterDate) {
        Matcher timeMatcher = LEADING_TIME_OF_DAY.matcher(textAfterDate == null ? "" : textAfterDate);
        return timeMatcher.find() ? date + " " + timeMatcher.group(1).replaceAll("\\s+", "") : date.toString();
    }

    private DayOfWeek parseDayOfWeek(String value) {
        return switch (value) {
            case "一" -> DayOfWeek.MONDAY;
            case "二" -> DayOfWeek.TUESDAY;
            case "三" -> DayOfWeek.WEDNESDAY;
            case "四" -> DayOfWeek.THURSDAY;
            case "五" -> DayOfWeek.FRIDAY;
            case "六" -> DayOfWeek.SATURDAY;
            default -> DayOfWeek.SUNDAY;
        };
    }

    private String normalizeDuration(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = normalizeDirectAnswer("duration", value);
        if (VALID_DURATION.matcher(normalized).matches()) {
            return normalized;
        }
        Matcher matcher = DURATION_IN_TEXT.matcher(normalized);
        return matcher.find() ? matcher.group(1) : "";
    }

    private boolean isSafeDirectAnswer(String field, String message) {
        String normalized = normalizeDirectAnswer(field, message);
        return switch (field) {
            case "origin" -> isSafeLocation(normalized);
            case "destination" -> RECOMMEND_DESTINATION.matcher(message.trim()).matches()
                    || isSafeLocation(normalized);
            case "departure_time" -> !normalizeDepartureTime(normalized).isBlank();
            case "duration" -> !normalizeDuration(normalized).isBlank();
            default -> false;
        };
    }

    private boolean isSafeLocation(String value) {
        if (value == null || value.isBlank() || value.length() > 60) {
            return false;
        }
        if (LOCATION_QUESTION_OR_COMMAND.matcher(value).matches()
                || value.equals("这里") || value.equals("那里") || value.equals("附近")
                || value.equals("随便") || value.equals("你猜")
                || value.matches(".*[，,。；;！!？?].*")
                || value.matches(".*(?:我在|我从|现在出发|想去|出去|游玩|玩一|回来|睡觉|"
                + "我去|我要|要去|有没有|有没|近点|近一点|可以去哪里).*")) {
            return false;
        }
        return value.chars().noneMatch(Character::isISOControl);
    }

    private String questionFor(String field) {
        return switch (field) {
            case "origin" -> "你从哪里出发？请给我具体的城市或地区。";
            case "destination" -> "你想去哪里？请给我目的城市或地区；如果还没想好，可以直接说“帮我推荐”。";
            case "departure_time" -> "你准备什么时候出发？可以说“现在出发”“明天”“周六”或具体日期。";
            case "duration" -> "这次准备玩多久？例如“半天”“1天”或“2天”。";
            default -> "请补充完整的出行信息。";
        };
    }

    public record TripPlanTurn(String reply, boolean completed) {
    }

    private record PreferenceRule(Pattern pattern, String label) {
    }
}
