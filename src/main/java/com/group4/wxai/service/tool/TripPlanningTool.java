package com.group4.wxai.service.tool;

import com.group4.wxai.model.TripPlanRequest;
import com.group4.wxai.service.llm.LlmService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class TripPlanningTool implements AiTool {

    public static final String NAME = "trip_plan";
    private static final Pattern ISO_DATE = Pattern.compile("\\b(\\d{4}-\\d{2}-\\d{2})\\b");
    private static final String INPUT_SCHEMA = """
            {
              "type":"object",
              "properties":{
                "origin":{"type":"string","minLength":1,"description":"出发城市或地区，禁止猜测"},
                "destination":{"type":"string","minLength":1,"description":"用户确认的目的城市或地区"},
                "departure_time":{"type":"string","minLength":1,"description":"明确的出行日期和可选出发时刻"},
                "duration":{"type":"string","minLength":1,"description":"出行时长，例如半天、1天、2天"}
              },
              "required":["origin","destination","departure_time","duration"],
              "additionalProperties":false
            }
            """;

    private final WeatherTool weatherTool;
    private final PlaceSearchTool placeSearchTool;
    private final WebSearchTool webSearchTool;
    private final LlmService llmService;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "在出发地、目的地、出行时间和时长全部确认后，依次查询天气、高德地点和联网资料并制定行程";
    }

    @Override
    public String inputJsonSchema() {
        return INPUT_SCHEMA;
    }

    @Override
    public String execute(Map<String, String> arguments) {
        TripPlanRequest request = TripPlanRequest.fromSlots(arguments);
        requireComplete(request);

        String weather = weatherTool.execute(Map.of(
                "location", request.destination(),
                "date", weatherDate(request.departureTime())
        ));
        String places = placeSearchTool.execute(Map.of(
                "location", request.destination(),
                "keyword", "旅游景点"
        ));
        String web = webSearchTool.execute(Map.of(
                "query", request.destination() + " " + request.departureTime() + " "
                        + request.duration() + " 旅行攻略 营业时间 预约 最新"
        ));

        return llmService.chatText("trip-plan-tool", buildPlanningPrompt(request, weather, places, web), List.of());
    }

    private void requireComplete(TripPlanRequest request) {
        if (!request.missingFields().isEmpty()) {
            throw new IllegalArgumentException("出行规划信息不完整，缺少："
                    + String.join("、", request.missingFields()) + "。请先补全，暂不调用工具。");
        }
        if (request.needsDestinationRecommendation()) {
            throw new IllegalArgumentException("请先从推荐结果中确认一个目的地，再生成完整行程。");
        }
        validateText(request.origin(), "出发地", 60);
        validateText(request.destination(), "目的地", 60);
        validateText(request.departureTime(), "出行时间", 80);
        validateText(request.duration(), "出行时长", 30);
    }

    private void validateText(String value, String label, int maxLength) {
        if (value.length() > maxLength || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(label + "格式无法识别，请换一种更简短的说法。");
        }
    }

    private String weatherDate(String departureTime) {
        Matcher matcher = ISO_DATE.matcher(departureTime);
        if (matcher.find()) {
            return matcher.group(1);
        }
        if (departureTime.contains("明天")) {
            return "TOMORROW";
        }
        if (departureTime.contains("后天")) {
            return "DAY_AFTER_TOMORROW";
        }
        if (departureTime.contains("今天")) {
            return "TODAY";
        }
        throw new IllegalArgumentException("出行时间需要具体到日期，例如“明天”或“2026-08-22”。");
    }

    private String buildPlanningPrompt(TripPlanRequest request, String weather, String places, String web) {
        return """
                请根据下面已经确认的参数和工具结果，生成一份可执行的中文出行计划。
                只使用给出的信息；资料冲突时明确说明，不要编造路线耗时、票价、营业时间或预约要求。
                按时间顺序安排，说明天气影响、建议携带物品，并把仍需用户自行确认的事项写清楚。
                输出自然的微信聊天文本，不要输出 JSON、占位符或内部工具名称。

                已确认参数：
                出发地：%s
                目的地：%s
                出行时间：%s
                出行时长：%s

                天气资料：
                %s

                高德地点资料：
                %s

                联网资料：
                %s
                """.formatted(
                request.origin(), request.destination(), request.departureTime(), request.duration(),
                weather, places, web
        );
    }
}
