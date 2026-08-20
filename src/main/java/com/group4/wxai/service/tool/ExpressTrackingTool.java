package com.group4.wxai.service.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ExpressTrackingTool implements AiTool {

    public static final String NAME = "express_tracking";
    private static final Map<String, String> COMPANY_CODES = Map.ofEntries(
            Map.entry("SF", "shunfeng"), Map.entry("顺丰", "shunfeng"),
            Map.entry("ZTO", "zhongtong"), Map.entry("中通", "zhongtong"),
            Map.entry("YTO", "yuantong"), Map.entry("圆通", "yuantong"),
            Map.entry("STO", "shentong"), Map.entry("申通", "shentong"),
            Map.entry("YUNDA", "yunda"), Map.entry("韵达", "yunda"),
            Map.entry("JD", "jd"), Map.entry("京东", "jd"),
            Map.entry("EMS", "ems"), Map.entry("邮政", "youzhengguonei")
    );
    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;

    @Value("${team-tools.express.api-url:https://poll.kuaidi100.com/poll/query.do}")
    private String apiUrl;
    @Value("${team-tools.express.customer:}")
    private String customer;
    @Value("${team-tools.express.api-key:}")
    private String apiKey;

    @Override
    public String name() { return NAME; }

    @Override
    public String description() {
        return "通过快递100查询物流；快递公司编码和单号必须明确，隐私运单需要手机号后四位。";
    }

    @Override
    public String inputJsonSchema() {
        return """
                {"type":"object","properties":{"company":{"type":"string"},
                "tracking_no":{"type":"string"},"phone":{"type":"string"}},
                "required":["company","tracking_no"],"additionalProperties":false}
                """;
    }

    @Override
    public String execute(Map<String, String> arguments) {
        if (customer == null || customer.isBlank() || apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("快递工具尚未配置，请填写 EXPRESS_CUSTOMER 和 EXPRESS_API_KEY。");
        }
        String company = normalizeCompany(ToolArguments.required(
                arguments, "company", "请告诉我快递公司；不知道时请先查看面单或物流通知。"));
        String trackingNo = ToolArguments.required(arguments, "tracking_no", "请提供快递单号。");
        String phone = ToolArguments.text(arguments, "phone").replaceAll("[^0-9]", "");
        if (phone.length() == 11) phone = phone.substring(7);
        if (!phone.isEmpty() && phone.length() != 4) {
            throw new IllegalArgumentException("手机号请提供后四位，或完整 11 位手机号。");
        }
        try {
            StringBuilder param = new StringBuilder("{\"com\":\"").append(ToolArguments.escape(company))
                    .append("\",\"num\":\"").append(ToolArguments.escape(trackingNo)).append("\"");
            if (!phone.isEmpty()) param.append(",\"phone\":\"").append(phone).append("\"");
            param.append("}");
            String sign = md5(param + apiKey + customer).toUpperCase();
            FormBody body = new FormBody.Builder(StandardCharsets.UTF_8)
                    .add("customer", customer).add("sign", sign).add("param", param.toString()).build();
            Request request = new Request.Builder().url(apiUrl).post(body).build();
            try (Response response = okHttpClient.newCall(request).execute()) {
                String responseBody = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) {
                    throw new IllegalStateException("快递 API 请求失败，HTTP " + response.code() + "。");
                }
                JsonNode root = objectMapper.readTree(responseBody);
                if (!root.path("data").isArray()) {
                    throw new IllegalStateException(root.path("message").asText("快递 API 没有返回物流轨迹。"));
                }
                StringBuilder reply = new StringBuilder("📦 ").append(trackingNo).append(" 物流：\n");
                int count = 0;
                for (JsonNode item : root.path("data")) {
                    if (count++ >= 8) break;
                    reply.append("- ").append(item.path("time").asText()).append(" ")
                            .append(item.path("context").asText()).append("\n");
                }
                return reply.toString().strip();
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("快递查询暂时不可用。", e);
        }
    }

    private String md5(CharSequence value) throws Exception {
        byte[] bytes = MessageDigest.getInstance("MD5")
                .digest(value.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte item : bytes) result.append(String.format("%02x", item));
        return result.toString();
    }

    private String normalizeCompany(String value) {
        String normalized = value.trim().replace("快递", "");
        return COMPANY_CODES.getOrDefault(normalized.toUpperCase(),
                COMPANY_CODES.getOrDefault(normalized, normalized));
    }
}
