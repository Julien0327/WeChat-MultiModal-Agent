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
    public String execute(Map<String, String> arguments) {
        return weatherService.getWeather(arguments.get("location"), arguments.get("date"));
    }
}
