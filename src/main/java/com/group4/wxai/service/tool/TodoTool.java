package com.group4.wxai.service.tool;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class TodoTool implements AiTool {

    public static final String NAME = "todo";
    private static final String FILE = "todos.json";
    private static final ZoneId BEIJING_ZONE = ZoneId.of("Asia/Shanghai");
    private final JsonDataStore store;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "新增、查看或完成当前微信用户的待办事项；新增时 title 必须来自用户原话。";
    }

    @Override
    public String inputJsonSchema() {
        return """
                {"type":"object","properties":{"action":{"type":"string","enum":["create","list","complete"]},
                "title":{"type":"string"},"due":{"type":"string"},"status":{"type":"string","enum":["active","done","all"]},
                "id":{"type":"string"}},"required":["action"],"additionalProperties":false}
                """;
    }

    @Override
    public String execute(ToolExecutionContext context, Map<String, String> arguments) {
        return switch (ToolArguments.required(arguments, "action", "请说明要新增、查看还是完成待办。")) {
            case "create" -> create(context.userId(), arguments);
            case "list" -> list(context.userId(), arguments);
            case "complete" -> complete(context.userId(), arguments);
            default -> throw new IllegalArgumentException("待办 action 只能是 create、list 或 complete。");
        };
    }

    @Override
    public String execute(Map<String, String> arguments) {
        return execute(ToolExecutionContext.anonymous(), arguments);
    }

    private String create(String userId, Map<String, String> arguments) {
        String title = ToolArguments.required(arguments, "title", "请告诉我要记录的待办内容。");
        String due = ToolArguments.text(arguments, "due");
        List<Map<String, Object>> items = store.load(FILE, userId);
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        items.add(Map.of(
                "id", id,
                "title", title,
                "due", due,
                "done", false,
                "created", LocalDate.now(BEIJING_ZONE).toString()
        ));
        store.save(FILE, userId, items);
        return "已记录待办 [" + id + "]：" + title + (due.isEmpty() ? "" : "（" + due + "）");
    }

    private String list(String userId, Map<String, String> arguments) {
        String status = ToolArguments.text(arguments, "status");
        if (status.isEmpty()) {
            status = "active";
        }
        if (!List.of("active", "done", "all").contains(status)) {
            throw new IllegalArgumentException("待办状态只能是 active、done 或 all。");
        }
        StringBuilder reply = new StringBuilder("待办事项：\n");
        int count = 0;
        for (Map<String, Object> item : store.load(FILE, userId)) {
            boolean done = Boolean.TRUE.equals(item.get("done"));
            if (!("all".equals(status) || (done && "done".equals(status)) || (!done && "active".equals(status)))) {
                continue;
            }
            count++;
            reply.append(count).append(". ").append(done ? "[已完成] " : "[未完成] ")
                    .append(item.get("title")).append("  ID:").append(item.get("id"));
            String due = String.valueOf(item.getOrDefault("due", ""));
            if (!due.isBlank()) {
                reply.append("  截止:").append(due);
            }
            reply.append("\n");
        }
        return count == 0 ? "没有符合条件的待办事项。" : reply.toString().strip();
    }

    private String complete(String userId, Map<String, String> arguments) {
        String id = ToolArguments.required(arguments, "id", "请提供要完成的待办 ID；可以先说“查看待办”。");
        List<Map<String, Object>> items = store.load(FILE, userId);
        for (int i = 0; i < items.size(); i++) {
            Map<String, Object> item = items.get(i);
            if (id.equals(String.valueOf(item.get("id")))) {
                Map<String, Object> updated = new java.util.LinkedHashMap<>(item);
                updated.put("done", true);
                items.set(i, updated);
                store.save(FILE, userId, items);
                return "已完成待办 [" + id + "]：" + item.get("title");
            }
        }
        throw new IllegalArgumentException("没有找到 ID 为 " + id + " 的待办。");
    }
}
