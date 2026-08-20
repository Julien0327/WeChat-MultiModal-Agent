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
import java.util.Map;
import java.util.Optional;
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
            ".*(?:没想好|还没想好|不知道去哪|不知道去哪里|帮我推荐|你推荐|推荐一个|推荐目的地).*"
    );
    private static final Pattern CANCEL = Pattern.compile(
            "^(?:算了|不用了|取消|不规划了|先不规划了|停止规划)[。！! ]*$"
    );
    private static final Pattern ORIGIN_IN_TEXT = Pattern.compile(
            "(?:^|[，,。；;！!？?\\s])(?:我)?(?:现在)?(?:在|从)"
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
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

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
        if (intent != null && intent.intent() == IntentType.TRIP_PLAN) {
            // On follow-up turns, only the user's raw message may fill or correct a slot.
            // Model-generated slots are intentionally ignored so they cannot overwrite or invent state.
            if (!continuingDraft) {
                draft = draft.merge(intent.slots());
            }
        }
        if (continuingDraft) {
            draft = applyExplicitTripDetails(draft, normalizedMessage);
        }
        if (continuingDraft && "destination".equals(awaitedField)
                && RECOMMENDATION_REFINEMENT.matcher(normalizedMessage).matches()) {
            draft = normalizeAndValidateDraft(draft);
            if (!draft.origin().isBlank() && !draft.departureTime().isBlank() && !draft.duration().isBlank()) {
                return recommendDestination(userId, draft.with("destination", TripPlanRequest.RECOMMEND_DESTINATION),
                        normalizedMessage);
            }
        }
        if (continuingDraft && !awaitedField.isBlank() && isSafeDirectAnswer(awaitedField, normalizedMessage)) {
            draft = applyDirectAnswer(draft, awaitedField, normalizedMessage);
        }
        draft = normalizeAndValidateDraft(draft);

        String missing = draft.nextMissingField();
        if (!missing.isBlank()) {
            drafts.put(userId, draft);
            return new TripPlanTurn(questionFor(missing), false);
        }

        if (draft.toRequest().needsDestinationRecommendation()) {
            return recommendDestination(userId, draft, "");
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

    private TripPlanTurn recommendDestination(String userId, TripPlanDraft draft, String preference) {
        TripPlanRequest request = draft.toRequest();
        String amapResults = placeSearchTool.execute(Map.of(
                "location", request.origin(),
                "keyword", "旅游景点"
        ));
        String webResults = webSearchTool.execute(Map.of(
                "query", "从" + request.origin() + "出发 " + request.departureTime() + " "
                        + request.duration() + " 周边游目的地推荐 最新攻略"
                        + (preference == null || preference.isBlank() ? "" : " 要求：" + preference)
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
                preference == null || preference.isBlank() ? "无" : preference,
                amapResults, webResults
        );
        String reply = llmService.chatText("trip-destination-recommendation", prompt, List.of());
        drafts.put(userId, new TripPlanDraft(
                draft.origin(), "", draft.departureTime(), draft.duration()
        ));
        return new TripPlanTurn(reply, false);
    }

    private TripPlanDraft normalizeAndValidateDraft(TripPlanDraft draft) {
        String origin = normalizeLocation(draft.origin(), "origin");
        String destination = normalizeDestination(draft.destination());
        String departureTime = normalizeDepartureTime(draft.departureTime());
        String duration = normalizeDuration(draft.duration());
        return new TripPlanDraft(origin, destination, departureTime, duration);
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
        return normalizeLocation(value, "destination");
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
                + "有没有|有没|近点|近一点|可以去哪里).*")) {
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
}
