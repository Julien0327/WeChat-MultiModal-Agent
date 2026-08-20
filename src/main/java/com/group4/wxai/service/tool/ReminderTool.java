package com.group4.wxai.service.tool;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class ReminderTool implements AiTool {

    public static final String NAME = "reminder";
    private static final String FILE = "reminders.json";
    private static final ZoneId BEIJING_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Pattern RELATIVE = Pattern.compile("(\\d+)\\s*(分钟|小时|天)后");
    private static final Pattern DAY_TIME = Pattern.compile(
            "(今天|明天|后天)?\\s*(早上|上午|中午|下午|晚上)?\\s*(\\d{1,2})"
                    + "(?:(?:[:：](\\d{1,2}))|点(\\d{1,2})?)?"
    );

    private final JsonDataStore store;

    @Override
    public String name() { return NAME; }

    @Override
    public String description() {
        return "设置、查看或取消微信主动提醒；提醒时间和内容必须由用户明确提供。";
    }

    @Override
    public String inputJsonSchema() {
        return """
                {"type":"object","properties":{"action":{"type":"string","enum":["set","list","cancel"]},
                "time":{"type":"string"},"content":{"type":"string"},
                "repeat":{"type":"string","enum":["once","daily","weekly"]},"id":{"type":"string"}},
                "required":["action"],"additionalProperties":false}
                """;
    }

    @Override
    public String execute(ToolExecutionContext context, Map<String, String> arguments) {
        return switch (ToolArguments.required(arguments, "action", "请说明要设置、查看还是取消提醒。")) {
            case "set" -> set(context.userId(), arguments);
            case "list" -> list(context.userId());
            case "cancel" -> cancel(context.userId(), arguments);
            default -> throw new IllegalArgumentException("提醒 action 只能是 set、list 或 cancel。");
        };
    }

    @Override
    public String execute(Map<String, String> arguments) {
        return execute(ToolExecutionContext.anonymous(), arguments);
    }

    private String set(String userId, Map<String, String> arguments) {
        String timeText = ToolArguments.required(arguments, "time", "你希望我什么时候提醒？");
        String content = ToolArguments.required(arguments, "content", "你希望我提醒什么？");
        String repeat = ToolArguments.text(arguments, "repeat");
        if (repeat.isEmpty()) repeat = "once";
        if (!List.of("once", "daily", "weekly").contains(repeat)) {
            throw new IllegalArgumentException("重复方式只能是 once、daily 或 weekly。");
        }
        Instant scheduledAt = parseTime(timeText, Instant.now());
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        List<Map<String, Object>> items = store.load(FILE, userId);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("content", content);
        item.put("scheduledAt", scheduledAt.toString());
        item.put("repeat", repeat);
        item.put("active", true);
        items.add(item);
        store.save(FILE, userId, items);
        return "已设置提醒 [" + id + "]：" + scheduledAt.atZone(BEIJING_ZONE).format(DISPLAY)
                + " 提醒你“" + content + "”" + ("once".equals(repeat) ? "" : "，重复：" + repeat);
    }

    private String list(String userId) {
        StringBuilder reply = new StringBuilder("提醒事项：\n");
        int count = 0;
        for (Map<String, Object> item : store.load(FILE, userId)) {
            if (!Boolean.TRUE.equals(item.get("active"))) continue;
            count++;
            Instant time = Instant.parse(String.valueOf(item.get("scheduledAt")));
            reply.append(count).append(". [").append(item.get("id")).append("] ")
                    .append(time.atZone(BEIJING_ZONE).format(DISPLAY)).append(" ")
                    .append(item.get("content")).append("\n");
        }
        return count == 0 ? "当前没有有效提醒。" : reply.toString().strip();
    }

    private String cancel(String userId, Map<String, String> arguments) {
        String id = ToolArguments.required(arguments, "id", "请提供要取消的提醒 ID；可以先说“查看提醒”。");
        List<Map<String, Object>> items = store.load(FILE, userId);
        for (int i = 0; i < items.size(); i++) {
            if (id.equals(String.valueOf(items.get(i).get("id")))) {
                Map<String, Object> updated = new LinkedHashMap<>(items.get(i));
                updated.put("active", false);
                items.set(i, updated);
                store.save(FILE, userId, items);
                return "已取消提醒 [" + id + "]。";
            }
        }
        throw new IllegalArgumentException("没有找到 ID 为 " + id + " 的提醒。");
    }

    public List<DueReminder> due(Instant now) {
        List<DueReminder> due = new ArrayList<>();
        store.loadAll(FILE).forEach((userId, items) -> items.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("active")))
                .filter(item -> !Instant.parse(String.valueOf(item.get("scheduledAt"))).isAfter(now))
                .forEach(item -> due.add(new DueReminder(userId, String.valueOf(item.get("id")),
                        String.valueOf(item.get("content"))))));
        return due;
    }

    public void markDelivered(String userId, String id, Instant deliveredAt) {
        List<Map<String, Object>> items = store.load(FILE, userId);
        for (int i = 0; i < items.size(); i++) {
            Map<String, Object> item = items.get(i);
            if (!id.equals(String.valueOf(item.get("id")))) continue;
            Map<String, Object> updated = new LinkedHashMap<>(item);
            String repeat = String.valueOf(item.getOrDefault("repeat", "once"));
            if ("daily".equals(repeat)) {
                updated.put("scheduledAt", deliveredAt.plus(Duration.ofDays(1)).toString());
            } else if ("weekly".equals(repeat)) {
                updated.put("scheduledAt", deliveredAt.plus(Duration.ofDays(7)).toString());
            } else {
                updated.put("active", false);
            }
            items.set(i, updated);
            store.save(FILE, userId, items);
            return;
        }
    }

    private Instant parseTime(String text, Instant now) {
        String normalized = text.trim();
        Matcher relative = RELATIVE.matcher(normalized);
        if (relative.matches()) {
            long value = Long.parseLong(relative.group(1));
            Duration duration = switch (relative.group(2)) {
                case "分钟" -> Duration.ofMinutes(value);
                case "小时" -> Duration.ofHours(value);
                default -> Duration.ofDays(value);
            };
            return now.plus(duration);
        }
        try {
            LocalDateTime dateTime = LocalDateTime.parse(normalized,
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            return dateTime.atZone(BEIJING_ZONE).toInstant();
        } catch (Exception ignored) {
            // Continue with common Chinese time expressions.
        }
        Matcher matcher = DAY_TIME.matcher(normalized);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("无法识别提醒时间，请说“10分钟后”“明天早上8点”或 yyyy-MM-dd HH:mm。");
        }
        LocalDate date = LocalDate.now(BEIJING_ZONE);
        if ("明天".equals(matcher.group(1))) date = date.plusDays(1);
        if ("后天".equals(matcher.group(1))) date = date.plusDays(2);
        int hour = Integer.parseInt(matcher.group(3));
        String minuteText = matcher.group(4) != null ? matcher.group(4) : matcher.group(5);
        int minute = minuteText == null ? 0 : Integer.parseInt(minuteText);
        String period = matcher.group(2);
        if (("下午".equals(period) || "晚上".equals(period)) && hour < 12) hour += 12;
        if ("中午".equals(period) && hour < 11) hour += 12;
        LocalDateTime dateTime = LocalDateTime.of(date, LocalTime.of(hour, minute));
        Instant result = dateTime.atZone(BEIJING_ZONE).toInstant();
        if (matcher.group(1) == null && !result.isAfter(now)) result = result.plus(Duration.ofDays(1));
        return result;
    }

    public record DueReminder(String userId, String id, String content) { }
}
