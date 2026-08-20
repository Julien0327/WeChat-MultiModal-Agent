package com.group4.wxai.service.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class HistoryTodayTool implements AiTool {

    public static final String NAME = "history_today";
    private static final ZoneId BEIJING_ZONE = ZoneId.of("Asia/Shanghai");

    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;

    @Value("${team-tools.history.api-url:}")
    private String apiUrl;

    @Value("${team-tools.history.api-key:}")
    private String apiKey;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "查询某月某日历史上发生的重要事件；月和日未给出时使用北京时间今天。";
    }

    @Override
    public String inputJsonSchema() {
        return """
                {"type":"object","properties":{"month":{"type":"integer","minimum":1,"maximum":12},
                "day":{"type":"integer","minimum":1,"maximum":31}},"additionalProperties":false}
                """;
    }

    @Override
    public String execute(Map<String, String> arguments) {
        requireConfiguration();
        LocalDate today = LocalDate.now(BEIJING_ZONE);
        int month = parse(arguments.get("month"), today.getMonthValue(), 1, 12, "月份");
        int day = parse(arguments.get("day"), today.getDayOfMonth(), 1, 31, "日期");
        HttpUrl base = HttpUrl.parse(apiUrl);
        if (base == null) {
            throw new IllegalStateException("历史 API 地址格式不正确，请检查 HISTORY_API_URL。");
        }
        HttpUrl url = base.newBuilder().addQueryParameter("key", apiKey)
                .addQueryParameter("date", month + "/" + day).build();
        JsonNode root = get(url);
        JsonNode result = root.path("result");
        if (!result.isArray() || result.isEmpty()) {
            throw new IllegalStateException(root.path("reason").asText("历史 API 没有返回事件。"));
        }
        StringBuilder reply = new StringBuilder("📅 ").append(month).append("月").append(day)
                .append("日，历史上的今天：\n");
        for (int i = 0; i < Math.min(5, result.size()); i++) {
            JsonNode item = result.get(i);
            reply.append(i + 1).append(". ")
                    .append(item.path("date").asText()).append("：")
                    .append(item.path("title").asText()).append("\n");
        }
        return reply.toString().strip();
    }

    private JsonNode get(HttpUrl url) {
        try (Response response = okHttpClient.newCall(new Request.Builder().url(url).get().build()).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IllegalStateException("历史 API 请求失败，HTTP " + response.code() + "。");
            }
            return objectMapper.readTree(body);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("历史 API 暂时不可用。", e);
        }
    }

    private void requireConfiguration() {
        if (apiUrl == null || apiUrl.isBlank() || apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("历史工具尚未配置，请填写 HISTORY_API_URL 和 HISTORY_API_KEY。");
        }
    }

    private int parse(String value, int fallback, int min, int max, String label) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < min || parsed > max) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + "格式不正确。");
        }
    }
}
