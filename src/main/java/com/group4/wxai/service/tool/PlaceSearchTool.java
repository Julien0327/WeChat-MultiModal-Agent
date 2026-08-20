package com.group4.wxai.service.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.group4.wxai.config.WeatherProperties;
import lombok.RequiredArgsConstructor;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class PlaceSearchTool implements AiTool {

    public static final String NAME = "place_search";
    private static final int MAX_RESULTS = 5;

    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;
    private final WeatherProperties weatherProperties;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "使用高德地图在指定城市内搜索景点、餐厅、医院、学校等地点";
    }

    @Override
    public String inputJsonSchema() {
        return """
                {"type":"object","properties":{
                  "location":{"type":"string","description":"用户当前消息明确提供的城市或地区"},
                  "keyword":{"type":"string","description":"要查找的地点类别或名称"}},
                 "required":["location","keyword"],"additionalProperties":false}
                """.replaceAll("\\s+", "");
    }

    @Override
    public String execute(Map<String, String> arguments) {
        if (!weatherProperties.isConfigured()) {
            throw new IllegalStateException("高德 Web 服务 Key 未配置");
        }
        String location = requireLocation(arguments);
        String keyword = requireKeyword(arguments);

        HttpUrl baseUrl = HttpUrl.parse(weatherProperties.getBaseUrl().replaceFirst("/+$", "") + "/place/text");
        if (baseUrl == null) {
            throw new IllegalStateException("高德地点搜索接口地址无效");
        }
        HttpUrl url = baseUrl.newBuilder()
                .addQueryParameter("key", weatherProperties.getApiKey())
                .addQueryParameter("keywords", keyword)
                .addQueryParameter("city", location)
                .addQueryParameter("citylimit", "true")
                .addQueryParameter("offset", Integer.toString(MAX_RESULTS))
                .addQueryParameter("page", "1")
                .addQueryParameter("extensions", "base")
                .addQueryParameter("output", "JSON")
                .build();

        JsonNode response = getJson(url);
        if (!"1".equals(response.path("status").asText())) {
            throw new IllegalStateException("高德地点搜索失败: " + response.path("info").asText("unknown"));
        }
        JsonNode pois = response.path("pois");
        if (!pois.isArray() || pois.isEmpty()) {
            return "在“" + location + "”没有找到与“" + keyword + "”相关的地点，请换个关键词试试。";
        }
        List<JsonNode> consistentPois = new ArrayList<>();
        for (JsonNode poi : pois) {
            if (matchesRequestedArea(location, poi)) {
                consistentPois.add(poi);
                if (consistentPois.size() == MAX_RESULTS) {
                    break;
                }
            }
        }
        if (consistentPois.isEmpty()) {
            return "高德返回的结果不属于“" + location + "”，程序已拦截这些外地数据。"
                    + "请把地点写得更完整一些，例如城市加区县。";
        }
        return formatPlaces(location, keyword, consistentPois);
    }

    private String formatPlaces(String location, String keyword, List<JsonNode> pois) {
        StringBuilder reply = new StringBuilder("📍 ")
                .append(location).append(" · ").append(keyword).append('\n');
        int resultCount = Math.min(MAX_RESULTS, pois.size());
        for (int index = 0; index < resultCount; index++) {
            JsonNode poi = pois.get(index);
            reply.append(index + 1).append(". ")
                    .append(textOrDefault(poi.path("name"), "未命名地点"))
                    .append('\n');
            reply.append("   地址：").append(formatAddress(poi)).append('\n');
            String type = firstCategory(textValue(poi.path("type")));
            if (!type.isBlank()) {
                reply.append("   类型：").append(type).append('\n');
            }
        }
        reply.append("\n数据来源：高德地图");
        return reply.toString();
    }

    private String formatAddress(JsonNode poi) {
        List<String> parts = new ArrayList<>();
        addDistinct(parts, textValue(poi.path("cityname")));
        addDistinct(parts, textValue(poi.path("adname")));
        addDistinct(parts, textValue(poi.path("address")));
        return parts.isEmpty() ? "地址暂未提供" : String.join("", parts);
    }

    private void addDistinct(List<String> parts, String value) {
        if (!value.isBlank() && parts.stream().noneMatch(value::equals)) {
            parts.add(value);
        }
    }

    private String firstCategory(String type) {
        int separator = type.indexOf(';');
        return separator < 0 ? type : type.substring(0, separator);
    }

    private String requireArgument(Map<String, String> arguments, String key, String label, int maxLength) {
        String value = arguments.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("请提供要查询的" + label + "。");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(label + "格式无法识别，请换一种更简短的说法。");
        }
        return normalized;
    }

    private String requireLocation(Map<String, String> arguments) {
        String location = requireArgument(arguments, "location", "城市或地区", 40);
        if (location.matches(".*[，,。；;！!？?].*")
                || location.matches(".*(?:有没有|有没|哪里|什么|近点|回来|睡觉|今天|明天|"
                + "晚上|现在出发|我在|我从|想去|出去).*")) {
            throw new IllegalArgumentException("地点格式无法识别，请输入明确的城市或地区名称。");
        }
        return location;
    }

    private String requireKeyword(Map<String, String> arguments) {
        String keyword = requireArgument(arguments, "keyword", "地点关键词", 40);
        if (keyword.matches(".*[，,。；;！!？?].*")
                || keyword.matches(".*(?:有没有|有没|回来|睡觉|今天|明天|晚上|现在|"
                + "近点|近一点|可以去哪里).*")) {
            throw new IllegalArgumentException("地点关键词格式无法识别，请输入景点、餐厅等简短类别。");
        }
        return keyword;
    }

    private boolean matchesRequestedArea(String location, JsonNode poi) {
        String requested = normalizeAdministrativeName(location);
        if (requested.isBlank()) {
            return false;
        }
        String returnedArea = normalizeAdministrativeName(
                textValue(poi.path("pname"))
                        + textValue(poi.path("cityname"))
                        + textValue(poi.path("adname"))
                        + textValue(poi.path("address"))
        );
        return !returnedArea.isBlank() && returnedArea.contains(requested);
    }

    private String normalizeAdministrativeName(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "")
                .replaceAll("特别行政区|自治区|自治州|省|市|区|县|镇|乡|街道", "")
                .trim();
    }

    private JsonNode getJson(HttpUrl url) {
        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "group-4-wx-ai/1.0")
                .get()
                .build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IllegalStateException("高德地点搜索请求失败: status=" + response.code());
            }
            return objectMapper.readTree(body);
        } catch (IOException e) {
            throw new IllegalStateException("高德地点搜索服务暂时不可用", e);
        }
    }

    private String textValue(JsonNode value) {
        if (value.isValueNode()) {
            return value.asText("").trim();
        }
        if (value.isArray()) {
            for (JsonNode item : value) {
                if (item.isValueNode() && !item.asText("").isBlank()) {
                    return item.asText().trim();
                }
            }
        }
        return "";
    }

    private String textOrDefault(JsonNode value, String fallback) {
        String text = textValue(value);
        return text.isBlank() ? fallback : text;
    }
}
