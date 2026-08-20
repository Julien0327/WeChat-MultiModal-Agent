package com.group4.wxai.service.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.group4.wxai.config.WeatherProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class WeatherService {

    private static final String GEOCODING_URL = "https://geocoding-api.open-meteo.com/v1/search";
    private static final String FORECAST_URL = "https://api.open-meteo.com/v1/forecast";
    private static final int FORECAST_DAYS = 7;
    private static final Pattern ADMINISTRATIVE_LOCATION = Pattern.compile(
            "^(.+?(?:省|自治区|特别行政区|市))(.+)$"
    );
    private static final Pattern SAFE_LOCATION = Pattern.compile("^[\\p{L}\\p{N} .,'·-]{2,80}$");
    private static final DateTimeFormatter DATE_LABEL = DateTimeFormatter.ofPattern(
            "M月d日 E", Locale.SIMPLIFIED_CHINESE
    );

    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;
    private final WeatherProperties weatherProperties;

    public String getWeather(String requestedLocation, String dateSelector) {
        String locationQuery = validateLocation(requestedLocation);
        if (weatherProperties.isConfigured()) {
            if (requestsSevenDayForecast(dateSelector)) {
                try {
                    return getSevenDayWeatherWithAmapLocation(locationQuery, dateSelector);
                } catch (Exception e) {
                    log.warn("高德地点解析或七日天气查询失败，降级到 Open-Meteo 地点解析: location={}, reason={}",
                            locationQuery, e.getMessage());
                }
            } else {
                try {
                    return getAmapWeather(locationQuery, dateSelector);
                } catch (Exception e) {
                    log.warn("高德天气查询失败，降级到 Open-Meteo: location={}, reason={}",
                            locationQuery, e.getMessage());
                }
            }
        }
        try {
            ResolvedLocation location = resolveLocation(locationQuery);
            JsonNode forecast = fetchForecast(location);
            return formatWeather(location, forecast, dateSelector);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("天气查询失败: location={}", locationQuery, e);
            throw new IllegalStateException("天气服务暂时不可用，请稍后重试。", e);
        }
    }

    private boolean requestsSevenDayForecast(String dateSelector) {
        if (dateSelector == null) {
            return false;
        }
        String normalized = dateSelector.trim().toUpperCase(Locale.ROOT);
        return Set.of(
                "NEXT_7_DAYS", "未来七天", "未来7天", "最近七天", "最近7天",
                "近七天", "近7天", "本周", "这周"
        ).contains(normalized);
    }

    private String validateLocation(String requestedLocation) {
        if (requestedLocation == null || requestedLocation.isBlank()) {
            throw new IllegalArgumentException("请告诉我你要查询哪个城市或地区的天气。");
        }
        String location = requestedLocation.trim();
        if (!SAFE_LOCATION.matcher(location).matches()) {
            throw new IllegalArgumentException("地点格式无法识别，请输入城市或地区名称，例如“成都”或“北京市海淀区”。");
        }
        return location;
    }

    private String getAmapWeather(String requestedLocation, String dateSelector) throws IOException {
        AmapLocation location = resolveAmapLocation(requestedLocation);
        JsonNode liveResponse = fetchAmapWeather(location.adcode(), "base");
        JsonNode forecastResponse = fetchAmapWeather(location.adcode(), "all");
        return formatAmapWeather(location, liveResponse, forecastResponse, dateSelector);
    }

    private String getSevenDayWeatherWithAmapLocation(String requestedLocation,
                                                      String dateSelector) throws IOException {
        AmapLocation amapLocation = resolveAmapLocation(requestedLocation);
        if (!Double.isFinite(amapLocation.latitude()) || !Double.isFinite(amapLocation.longitude())) {
            throw new IllegalStateException("高德地点解析结果缺少经纬度");
        }
        ResolvedLocation location = new ResolvedLocation(
                amapLocation.name(), amapLocation.province(), amapLocation.country(),
                amapLocation.latitude(), amapLocation.longitude()
        );
        return formatWeather(location, fetchForecast(location), dateSelector);
    }

    private AmapLocation resolveAmapLocation(String requestedLocation) throws IOException {
        HttpUrl url = amapUrl("/geocode/geo").newBuilder()
                .addQueryParameter("key", weatherProperties.getApiKey())
                .addQueryParameter("address", requestedLocation)
                .addQueryParameter("output", "JSON")
                .build();
        JsonNode response = getJson(url);
        requireAmapSuccess(response, "地点解析");
        JsonNode geocode = response.path("geocodes").path(0);
        String adcode = textValue(geocode.path("adcode"));
        if (adcode.isBlank()) {
            throw new IllegalArgumentException("没有找到“" + requestedLocation + "”，请补充省、市或区县名称。");
        }

        String district = textValue(geocode.path("district"));
        String city = textValue(geocode.path("city"));
        String province = textValue(geocode.path("province"));
        String displayName = !district.isBlank() ? district : (!city.isBlank() ? city : requestedLocation);
        double[] coordinates = parseAmapCoordinates(textValue(geocode.path("location")));
        return new AmapLocation(
                displayName, province, "中国", adcode, coordinates[0], coordinates[1]
        );
    }

    private double[] parseAmapCoordinates(String value) {
        if (value == null || value.isBlank()) {
            return new double[]{Double.NaN, Double.NaN};
        }
        String[] parts = value.split(",", 2);
        if (parts.length != 2) {
            return new double[]{Double.NaN, Double.NaN};
        }
        try {
            double longitude = Double.parseDouble(parts[0].trim());
            double latitude = Double.parseDouble(parts[1].trim());
            return new double[]{latitude, longitude};
        } catch (NumberFormatException ignored) {
            return new double[]{Double.NaN, Double.NaN};
        }
    }

    private JsonNode fetchAmapWeather(String adcode, String extensions) throws IOException {
        HttpUrl url = amapUrl("/weather/weatherInfo").newBuilder()
                .addQueryParameter("key", weatherProperties.getApiKey())
                .addQueryParameter("city", adcode)
                .addQueryParameter("extensions", extensions)
                .addQueryParameter("output", "JSON")
                .build();
        JsonNode response = getJson(url);
        requireAmapSuccess(response, "天气查询");
        return response;
    }

    private String formatAmapWeather(AmapLocation location, JsonNode liveResponse,
                                     JsonNode forecastResponse, String dateSelector) {
        JsonNode live = liveResponse.path("lives").path(0);
        JsonNode forecast = forecastResponse.path("forecasts").path(0);
        JsonNode casts = forecast.path("casts");
        if (!casts.isArray() || casts.isEmpty()) {
            throw new IllegalStateException("高德天气接口未返回逐日预报");
        }

        ArrayNode dates = objectMapper.createArrayNode();
        casts.forEach(cast -> dates.add(cast.path("date").asText("")));
        DateSelection selection = resolveDateSelection(dateSelector, dates);
        StringBuilder reply = new StringBuilder("📍 ").append(location.displayName()).append('\n');
        if (selection.startIndex() == 0 && selection.days() == 1 && live.isObject()) {
            appendAmapCurrent(reply, live);
        }
        for (int offset = 0; offset < selection.days(); offset++) {
            int index = selection.startIndex() + offset;
            appendAmapDaily(reply, casts.path(index), index);
        }

        String updateTime = textValue(live.path("reporttime"));
        if (updateTime.isBlank()) {
            updateTime = textValue(forecast.path("reporttime"));
        }
        if (!updateTime.isBlank()) {
            reply.append("\n数据更新时间：").append(updateTime);
        }
        return reply.toString();
    }

    private void appendAmapCurrent(StringBuilder reply, JsonNode live) {
        reply.append("现在：")
                .append(textOrDefault(live.path("weather"), "天气状况未知"))
                .append("，").append(textOrDefault(live.path("temperature"), "--")).append("℃")
                .append("，湿度 ").append(textOrDefault(live.path("humidity"), "--")).append('%')
                .append("，").append(textOrDefault(live.path("winddirection"), "未知")).append("风")
                .append(textOrDefault(live.path("windpower"), "--")).append("级")
                .append('\n');
    }

    private void appendAmapDaily(StringBuilder reply, JsonNode cast, int index) {
        LocalDate date = LocalDate.parse(cast.path("date").asText());
        String dayWeather = textOrDefault(cast.path("dayweather"), "天气状况未知");
        String nightWeather = textOrDefault(cast.path("nightweather"), dayWeather);
        reply.append(relativeDateLabel(index)).append("（").append(DATE_LABEL.format(date)).append("）：")
                .append(dayWeather);
        if (!nightWeather.equals(dayWeather)) {
            reply.append("转").append(nightWeather);
        }
        reply.append("，")
                .append(textOrDefault(cast.path("nighttemp"), "--"))
                .append("～")
                .append(textOrDefault(cast.path("daytemp"), "--"))
                .append("℃，白天")
                .append(textOrDefault(cast.path("daywind"), "未知")).append("风")
                .append(textOrDefault(cast.path("daypower"), "--")).append("级")
                .append('\n');
    }

    private void requireAmapSuccess(JsonNode response, String operation) throws IOException {
        if (!"1".equals(response.path("status").asText())) {
            throw new IOException(operation + "失败: " + response.path("info").asText("unknown"));
        }
    }

    private HttpUrl amapUrl(String path) {
        String baseUrl = weatherProperties.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("高德天气接口地址未配置");
        }
        return requireUrl(baseUrl.replaceFirst("/+$", "") + path);
    }

    private String textValue(JsonNode value) {
        return value.isTextual() || value.isNumber() ? value.asText("").trim() : "";
    }

    private String textOrDefault(JsonNode value, String fallback) {
        String text = textValue(value);
        return text.isBlank() ? fallback : text;
    }

    private ResolvedLocation resolveLocation(String requestedLocation) throws IOException {
        for (String candidate : buildLocationCandidates(requestedLocation)) {
            JsonNode results = geocode(candidate).path("results");
            if (results.isArray() && !results.isEmpty()) {
                return selectBestLocation(results, requestedLocation);
            }
        }
        throw new IllegalArgumentException("没有找到“" + requestedLocation + "”，请换成更完整的城市或地区名称。");
    }

    private Set<String> buildLocationCandidates(String location) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(location);
        candidates.add(stripAdministrativeSuffix(location));

        Matcher matcher = ADMINISTRATIVE_LOCATION.matcher(location);
        if (matcher.matches()) {
            String parent = stripAdministrativeSuffix(matcher.group(1));
            String child = stripAdministrativeSuffix(matcher.group(2));
            candidates.add(child + ", " + parent);
            candidates.add(child);
        }
        candidates.removeIf(String::isBlank);
        return candidates;
    }

    private JsonNode geocode(String location) throws IOException {
        HttpUrl url = requireUrl(GEOCODING_URL).newBuilder()
                .addQueryParameter("name", location)
                .addQueryParameter("count", "10")
                .addQueryParameter("language", "zh")
                .addQueryParameter("format", "json")
                .build();
        return getJson(url);
    }

    private ResolvedLocation selectBestLocation(JsonNode results, String requestedLocation) {
        JsonNode best = results.get(0);
        int bestScore = scoreLocation(best, requestedLocation);
        for (int i = 1; i < results.size(); i++) {
            JsonNode candidate = results.get(i);
            int score = scoreLocation(candidate, requestedLocation);
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }

        double latitude = best.path("latitude").asDouble(Double.NaN);
        double longitude = best.path("longitude").asDouble(Double.NaN);
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)) {
            throw new IllegalStateException("地点解析结果缺少经纬度");
        }
        return new ResolvedLocation(
                best.path("name").asText(requestedLocation),
                best.path("admin1").asText(""),
                best.path("country").asText(""),
                latitude,
                longitude
        );
    }

    private int scoreLocation(JsonNode candidate, String requestedLocation) {
        String requested = normalizeLocation(requestedLocation);
        String name = normalizeLocation(candidate.path("name").asText(""));
        String fullName = normalizeLocation(
                candidate.path("name").asText("")
                        + candidate.path("admin1").asText("")
                        + candidate.path("admin2").asText("")
                        + candidate.path("country").asText("")
        );

        int score = 0;
        if (name.equals(requested)) {
            score += 1_000;
        } else if (name.startsWith(requested) || requested.startsWith(name)) {
            score += 500;
        }
        if (fullName.contains(requested)) {
            score += 200;
        }
        long population = candidate.path("population").asLong(0);
        score += Math.min(100, population <= 0 ? 0 : (int) Math.log10(population) * 10);
        return score;
    }

    private JsonNode fetchForecast(ResolvedLocation location) throws IOException {
        HttpUrl url = requireUrl(FORECAST_URL).newBuilder()
                .addQueryParameter("latitude", Double.toString(location.latitude()))
                .addQueryParameter("longitude", Double.toString(location.longitude()))
                .addQueryParameter("current",
                        "temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m")
                .addQueryParameter("daily",
                        "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset")
                .addQueryParameter("timezone", "auto")
                .addQueryParameter("forecast_days", Integer.toString(FORECAST_DAYS))
                .build();
        return getJson(url);
    }

    private JsonNode getJson(HttpUrl url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "group-4-wx-ai/1.0")
                .get()
                .build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("天气接口请求失败: status=" + response.code());
            }
            JsonNode json = objectMapper.readTree(body);
            if (json.path("error").asBoolean(false)) {
                throw new IOException("天气接口返回错误: " + json.path("reason").asText("unknown"));
            }
            return json;
        }
    }

    private String formatWeather(ResolvedLocation location, JsonNode forecast, String dateSelector) {
        JsonNode daily = forecast.path("daily");
        JsonNode dates = daily.path("time");
        if (!dates.isArray() || dates.isEmpty()) {
            throw new IllegalStateException("天气接口未返回逐日预报");
        }

        DateSelection selection = resolveDateSelection(dateSelector, dates);
        StringBuilder reply = new StringBuilder("📍 ").append(location.displayName()).append('\n');
        if (selection.startIndex() == 0 && selection.days() == 1) {
            appendCurrent(reply, forecast.path("current"));
        }

        for (int offset = 0; offset < selection.days(); offset++) {
            int index = selection.startIndex() + offset;
            appendDaily(reply, daily, dates, index);
        }

        String updateTime = forecast.path("current").path("time").asText("");
        if (!updateTime.isBlank()) {
            reply.append("\n数据更新时间：").append(updateTime.replace('T', ' '));
        }
        return reply.toString();
    }

    private DateSelection resolveDateSelection(String selector, JsonNode dates) {
        String normalized = selector == null ? "" : selector.trim().toUpperCase(Locale.ROOT);
        int days = switch (normalized) {
            case "NEXT_3_DAYS", "未来三天", "未来3天", "最近三天", "最近3天", "近三天", "近3天" -> 3;
            case "NEXT_7_DAYS", "未来七天", "未来7天", "最近七天", "最近7天", "近七天", "近7天", "本周", "这周" -> 7;
            default -> 1;
        };
        if (days > 1) {
            return new DateSelection(0, Math.min(days, dates.size()));
        }

        int relativeIndex = switch (normalized) {
            case "TOMORROW", "明天" -> 1;
            case "DAY_AFTER_TOMORROW", "AFTER_TOMORROW", "后天" -> 2;
            default -> 0;
        };
        if (relativeIndex > 0 || normalized.isBlank() || "TODAY".equals(normalized)
                || "今天".equals(normalized) || "现在".equals(normalized)) {
            if (relativeIndex >= dates.size()) {
                throw new IllegalArgumentException("该天气源暂时没有返回所选日期的预报。");
            }
            return new DateSelection(relativeIndex, 1);
        }

        try {
            LocalDate requestedDate = LocalDate.parse(normalized);
            for (int i = 0; i < dates.size(); i++) {
                if (requestedDate.toString().equals(dates.path(i).asText())) {
                    return new DateSelection(i, 1);
                }
            }
            throw new IllegalArgumentException("目前只能查询未来 " + FORECAST_DAYS + " 天的天气。");
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("没有识别出查询日期，请使用“今天”“明天”“后天”或具体日期。");
        }
    }

    private void appendCurrent(StringBuilder reply, JsonNode current) {
        if (!current.isObject()) {
            return;
        }
        reply.append("现在：")
                .append(weatherDescription(current.path("weather_code").asInt(-1)))
                .append("，").append(number(current.path("temperature_2m"))).append("℃")
                .append("（体感 ").append(number(current.path("apparent_temperature"))).append("℃）")
                .append("，湿度 ").append(integer(current.path("relative_humidity_2m"))).append('%')
                .append("，风速 ").append(number(current.path("wind_speed_10m"))).append(" km/h")
                .append("，当前降水 ").append(number(current.path("precipitation"))).append(" mm")
                .append('\n');
    }

    private void appendDaily(StringBuilder reply, JsonNode daily, JsonNode dates, int index) {
        LocalDate date = LocalDate.parse(dates.path(index).asText());
        reply.append(relativeDateLabel(index)).append("（").append(DATE_LABEL.format(date)).append("）：")
                .append(weatherDescription(valueAt(daily, "weather_code", index).asInt(-1)))
                .append("，")
                .append(number(valueAt(daily, "temperature_2m_min", index)))
                .append("～")
                .append(number(valueAt(daily, "temperature_2m_max", index)))
                .append("℃");

        JsonNode precipitation = valueAt(daily, "precipitation_probability_max", index);
        if (precipitation.isNumber()) {
            reply.append("，最高降水概率 ").append(integer(precipitation)).append('%');
        }
        reply.append('\n');
    }

    private JsonNode valueAt(JsonNode parent, String field, int index) {
        JsonNode values = parent.path(field);
        return values.isArray() && index < values.size() ? values.path(index) : objectMapper.nullNode();
    }

    private String relativeDateLabel(int index) {
        return switch (index) {
            case 0 -> "今天";
            case 1 -> "明天";
            case 2 -> "后天";
            default -> "第" + (index + 1) + "天";
        };
    }

    private String number(JsonNode value) {
        if (!value.isNumber()) {
            return "--";
        }
        return BigDecimal.valueOf(value.asDouble())
                .setScale(1, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    private String integer(JsonNode value) {
        return value.isNumber() ? Long.toString(Math.round(value.asDouble())) : "--";
    }

    private String weatherDescription(int code) {
        return switch (code) {
            case 0 -> "晴";
            case 1 -> "晴间多云";
            case 2 -> "多云";
            case 3 -> "阴";
            case 45, 48 -> "雾";
            case 51, 53, 55 -> "毛毛雨";
            case 56, 57 -> "冻毛毛雨";
            case 61 -> "小雨";
            case 63 -> "中雨";
            case 65 -> "大雨";
            case 66, 67 -> "冻雨";
            case 71 -> "小雪";
            case 73 -> "中雪";
            case 75 -> "大雪";
            case 77 -> "米雪";
            case 80, 81, 82 -> "阵雨";
            case 85, 86 -> "阵雪";
            case 95 -> "雷雨";
            case 96, 99 -> "雷雨伴冰雹";
            default -> "天气状况未知";
        };
    }

    private String stripAdministrativeSuffix(String value) {
        return value.trim().replaceFirst("(?:特别行政区|自治区|自治州|地区|省|市|区|县)$", "");
    }

    private String normalizeLocation(String value) {
        return stripAdministrativeSuffix(value)
                .replaceAll("[\\s,，·'-]", "")
                .toLowerCase(Locale.ROOT);
    }

    private HttpUrl requireUrl(String value) {
        HttpUrl url = HttpUrl.parse(value);
        if (url == null) {
            throw new IllegalStateException("天气接口地址无效");
        }
        return url;
    }

    private record DateSelection(int startIndex, int days) {
    }

    private record AmapLocation(
            String name,
            String province,
            String country,
            String adcode,
            double latitude,
            double longitude
    ) {
        String displayName() {
            List<String> parts = new ArrayList<>();
            addDistinct(parts, name);
            addDistinct(parts, province);
            addDistinct(parts, country);
            return String.join(" · ", parts);
        }

        private void addDistinct(List<String> parts, String value) {
            if (value != null && !value.isBlank() && parts.stream().noneMatch(value::equals)) {
                parts.add(value);
            }
        }
    }

    private record ResolvedLocation(
            String name,
            String admin1,
            String country,
            double latitude,
            double longitude
    ) {
        String displayName() {
            List<String> parts = new ArrayList<>();
            addDistinct(parts, name);
            addDistinct(parts, admin1);
            addDistinct(parts, country);
            return String.join(" · ", parts);
        }

        private void addDistinct(List<String> parts, String value) {
            if (value != null && !value.isBlank() && parts.stream().noneMatch(value::equals)) {
                parts.add(value);
            }
        }
    }
}
