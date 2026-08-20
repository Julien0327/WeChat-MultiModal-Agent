package com.group4.wxai.service.tool;

import com.group4.wxai.service.weather.WeatherService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class WeatherTool implements AiTool {

    public static final String NAME = "weather";

    private final WeatherService weatherService;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "查询指定城市或地区的实时天气及未来七天天气";
    }

    @Override
    public String inputJsonSchema() {
        return """
                {
                  "type":"object",
                  "properties":{
                    "location":{"type":"string","minLength":1,"description":"用户明确提供的城市或地区，禁止从历史记录猜测"},
                    "date":{"type":"string","description":"TODAY、TOMORROW、DAY_AFTER_TOMORROW、NEXT_3_DAYS、NEXT_7_DAYS 或 YYYY-MM-DD"}
                  },
                  "required":["location"],
                  "additionalProperties":false
                }
                """;
    }

    @Override
    public String execute(Map<String, String> arguments) {
        return weatherService.getWeather(arguments.get("location"), arguments.get("date"));
    }
}
