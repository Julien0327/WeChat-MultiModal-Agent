package com.group4.wxai.service.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.group4.wxai.config.WebSearchProperties;
import lombok.RequiredArgsConstructor;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class WebSearchTool implements AiTool {

    public static final String NAME = "web_search";
    private static final int MAX_QUERY_LENGTH = 300;
    private static final int MAX_ANSWER_LENGTH = 900;
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;
    private final WebSearchProperties properties;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "搜索互联网中的实时信息、新闻、官网和近期变化，并返回来源链接";
    }

    @Override
    public String execute(Map<String, String> arguments) {
        if (!properties.isConfigured()) {
            throw new IllegalStateException("Tavily 搜索 API Key 未配置");
        }
        String query = requireQuery(arguments.get("query"));
        HttpUrl url = HttpUrl.parse(properties.getBaseUrl().replaceFirst("/+$", "") + "/search");
        if (url == null) {
            throw new IllegalStateException("Tavily 搜索接口地址无效");
        }

        ObjectNode body = objectMapper.createObjectNode();
        body.put("query", query);
        body.put("search_depth", "basic");
        body.put("topic", "general");
        body.put("include_answer", "basic");
        body.put("include_raw_content", false);
        body.put("max_results", properties.effectiveMaxResults());

        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + properties.getApiKey().trim())
                .header("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IllegalStateException("联网搜索请求失败: status=" + response.code());
            }
            return formatResults(query, objectMapper.readTree(responseBody));
        } catch (IOException e) {
            throw new IllegalStateException("联网搜索服务暂时不可用", e);
        }
    }

    private String requireQuery(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("请告诉我要搜索什么内容。");
        }
        String query = value.trim();
        if (query.length() > MAX_QUERY_LENGTH || query.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("搜索内容太长，请换一种更简短的说法。");
        }
        return query;
    }

    private String formatResults(String query, JsonNode response) {
        StringBuilder reply = new StringBuilder("🔎 联网搜索：").append(query).append('\n');
        String answer = cleanText(response.path("answer").asText(""));
        if (!answer.isBlank()) {
            reply.append('\n').append(truncate(answer, MAX_ANSWER_LENGTH)).append('\n');
        }

        JsonNode results = response.path("results");
        if (!results.isArray() || results.isEmpty()) {
            return reply.append("\n没有找到可靠的搜索结果，请换个关键词试试。").toString();
        }
        reply.append("\n来源：\n");
        int displayed = 0;
        for (JsonNode result : results) {
            String resultUrl = safeHttpUrl(result.path("url").asText(""));
            if (resultUrl.isBlank()) {
                continue;
            }
            displayed++;
            reply.append(displayed).append(". ")
                    .append(truncate(cleanText(result.path("title").asText("网页结果")), 80))
                    .append('\n').append(resultUrl).append('\n');
            if (displayed >= properties.effectiveMaxResults()) {
                break;
            }
        }
        if (displayed == 0) {
            reply.append("没有可用的来源链接。\n");
        }
        reply.append("\n数据来源：Tavily");
        return reply.toString();
    }

    private String safeHttpUrl(String value) {
        try {
            URI uri = URI.create(value.trim());
            if (("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null) {
                return uri.toString();
            }
        } catch (IllegalArgumentException ignored) {
        }
        return "";
    }

    private String cleanText(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength - 1) + "…";
    }
}
