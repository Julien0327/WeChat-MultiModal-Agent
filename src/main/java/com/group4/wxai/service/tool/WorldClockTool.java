package com.group4.wxai.service.tool;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class WorldClockTool implements AiTool {

    public static final String NAME = "world_clock";
    private static final ZoneId BEIJING_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Pattern UTC_OFFSET_PATTERN = Pattern.compile(
            "^(?:UTC|GMT)([+-])(\\d{1,2})(?::?(\\d{2}))?$", Pattern.CASE_INSENSITIVE
    );
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern(
            "yyyy年M月d日 EEEE HH:mm:ss", Locale.SIMPLIFIED_CHINESE
    );
    private static final Map<String, ZoneEntry> ZONES = createZones();

    private final Clock clock;

    public WorldClockTool() {
        this(Clock.systemUTC());
    }

    WorldClockTool(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "查询世界主要城市或指定 IANA/UTC 时区的当前日期和时间，自动处理夏令时";
    }

    @Override
    public String execute(Map<String, String> arguments) {
        String requestedLocation = requireLocation(arguments.get("location"));
        ZoneEntry zone = resolveZone(requestedLocation);
        Instant now = clock.instant();
        ZonedDateTime localTime = now.atZone(zone.zoneId());
        ZoneOffset localOffset = localTime.getOffset();
        ZoneOffset beijingOffset = now.atZone(BEIJING_ZONE).getOffset();

        StringBuilder reply = new StringBuilder("🕒 ").append(zone.displayName()).append('\n')
                .append("当地时间：").append(TIME_FORMATTER.format(localTime)).append('\n')
                .append("时区：").append(zone.zoneId().getId())
                .append("（UTC").append(formatOffset(localOffset)).append("）");

        int differenceSeconds = localOffset.getTotalSeconds() - beijingOffset.getTotalSeconds();
        if (differenceSeconds == 0) {
            reply.append("\n与北京时间相同");
        } else {
            reply.append("\n比北京时间")
                    .append(differenceSeconds > 0 ? "快" : "慢")
                    .append(formatDuration(Math.abs(differenceSeconds)));
        }
        return reply.toString();
    }

    private String requireLocation(String value) {
        if (value == null || value.isBlank()) {
            return "北京";
        }
        String location = value.trim();
        if (location.length() > 80 || location.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("城市或时区格式无法识别，请换一种更简短的说法。");
        }
        return location;
    }

    private ZoneEntry resolveZone(String value) {
        String normalized = normalizeAlias(value);
        ZoneEntry mapped = ZONES.get(normalized);
        if (mapped != null) {
            return mapped;
        }

        Matcher offsetMatcher = UTC_OFFSET_PATTERN.matcher(value.replace(" ", ""));
        if (offsetMatcher.matches()) {
            int hours = Integer.parseInt(offsetMatcher.group(2));
            int minutes = offsetMatcher.group(3) == null ? 0 : Integer.parseInt(offsetMatcher.group(3));
            if (hours <= 18 && minutes < 60 && (hours < 18 || minutes == 0)) {
                int totalSeconds = (hours * 60 + minutes) * 60;
                if ("-".equals(offsetMatcher.group(1))) {
                    totalSeconds = -totalSeconds;
                }
                ZoneOffset offset = ZoneOffset.ofTotalSeconds(totalSeconds);
                return new ZoneEntry(value.toUpperCase(Locale.ROOT), offset);
            }
        }

        try {
            ZoneId zoneId = ZoneId.of(value.trim());
            return new ZoneEntry(value.trim(), zoneId);
        } catch (Exception ignored) {
            throw new IllegalArgumentException(
                    "暂时无法确定“" + value + "”的时区，请换成城市名称，例如“东京”“伦敦”“纽约”，或输入 Asia/Tokyo。"
            );
        }
    }

    private String normalizeAlias(String value) {
        return value.trim().toLowerCase(Locale.ROOT)
                .replace("市", "")
                .replaceAll("[\\s·_-]+", "");
    }

    private String formatOffset(ZoneOffset offset) {
        int totalSeconds = offset.getTotalSeconds();
        String sign = totalSeconds >= 0 ? "+" : "-";
        int absoluteMinutes = Math.abs(totalSeconds) / 60;
        return String.format(Locale.ROOT, "%s%02d:%02d", sign, absoluteMinutes / 60, absoluteMinutes % 60);
    }

    private String formatDuration(int seconds) {
        int minutes = seconds / 60;
        int hours = minutes / 60;
        int remainingMinutes = minutes % 60;
        if (remainingMinutes == 0) {
            return hours + "小时";
        }
        return hours + "小时" + remainingMinutes + "分钟";
    }

    private static Map<String, ZoneEntry> createZones() {
        Map<String, ZoneEntry> zones = new LinkedHashMap<>();
        add(zones, "Asia/Shanghai", "北京时间", "中国", "北京", "上海", "广州", "深圳", "成都", "重庆", "江油", "beijing", "shanghai");
        add(zones, "Asia/Hong_Kong", "香港时间", "香港", "hongkong");
        add(zones, "Asia/Macau", "澳门时间", "澳门", "macau");
        add(zones, "Asia/Tokyo", "东京时间", "东京", "日本东京", "tokyo");
        add(zones, "Asia/Seoul", "首尔时间", "首尔", "韩国首尔", "seoul");
        add(zones, "Asia/Singapore", "新加坡时间", "新加坡", "singapore");
        add(zones, "Asia/Bangkok", "曼谷时间", "曼谷", "泰国曼谷", "bangkok");
        add(zones, "Asia/Kolkata", "新德里时间", "新德里", "印度新德里", "newdelhi");
        add(zones, "Asia/Dubai", "迪拜时间", "迪拜", "dubai");
        add(zones, "Europe/London", "伦敦时间", "伦敦", "英国伦敦", "london");
        add(zones, "Europe/Paris", "巴黎时间", "巴黎", "法国巴黎", "paris");
        add(zones, "Europe/Berlin", "柏林时间", "柏林", "德国柏林", "berlin");
        add(zones, "Europe/Moscow", "莫斯科时间", "莫斯科", "moscow");
        add(zones, "America/New_York", "纽约时间", "纽约", "美国纽约", "newyork");
        add(zones, "America/Chicago", "芝加哥时间", "芝加哥", "chicago");
        add(zones, "America/Denver", "丹佛时间", "丹佛", "denver");
        add(zones, "America/Los_Angeles", "洛杉矶时间", "洛杉矶", "美国洛杉矶", "losangeles");
        add(zones, "America/Toronto", "多伦多时间", "多伦多", "toronto");
        add(zones, "America/Vancouver", "温哥华时间", "温哥华", "vancouver");
        add(zones, "America/Sao_Paulo", "圣保罗时间", "圣保罗", "saopaulo");
        add(zones, "Australia/Sydney", "悉尼时间", "悉尼", "澳大利亚悉尼", "sydney");
        add(zones, "Australia/Melbourne", "墨尔本时间", "墨尔本", "melbourne");
        add(zones, "Pacific/Auckland", "奥克兰时间", "奥克兰", "新西兰奥克兰", "auckland");
        add(zones, "Pacific/Honolulu", "檀香山时间", "檀香山", "honolulu");
        add(zones, "UTC", "协调世界时", "utc", "gmt", "协调世界时", "世界标准时间");
        return Map.copyOf(zones);
    }

    private static void add(Map<String, ZoneEntry> zones, String zoneId, String displayName, String... aliases) {
        ZoneEntry entry = new ZoneEntry(displayName, ZoneId.of(zoneId));
        for (String alias : aliases) {
            zones.put(alias.toLowerCase(Locale.ROOT).replace("市", "").replaceAll("[\\s·_-]+", ""), entry);
        }
    }

    private record ZoneEntry(String displayName, ZoneId zoneId) {
    }
}
