package com.group4.wxai.service.tool;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ExpenseTool implements AiTool {

    public static final String NAME = "expense";
    private static final String FILE = "expenses.json";
    private static final ZoneId BEIJING_ZONE = ZoneId.of("Asia/Shanghai");
    private final JsonDataStore store;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "记录支出、查看账单明细或生成月度汇总；金额必须由用户明确提供。";
    }

    @Override
    public String inputJsonSchema() {
        return """
                {"type":"object","properties":{"action":{"type":"string","enum":["add","list","report"]},
                "amount":{"type":"number"},"category":{"type":"string"},"note":{"type":"string"},
                "date":{"type":"string"},"month":{"type":"string"}},"required":["action"],
                "additionalProperties":false}
                """;
    }

    @Override
    public String execute(ToolExecutionContext context, Map<String, String> arguments) {
        return switch (ToolArguments.required(arguments, "action", "请说明要记账、查账还是查看月报。")) {
            case "add" -> add(context.userId(), arguments);
            case "list" -> list(context.userId(), arguments, false);
            case "report" -> list(context.userId(), arguments, true);
            default -> throw new IllegalArgumentException("账单 action 只能是 add、list 或 report。");
        };
    }

    @Override
    public String execute(Map<String, String> arguments) {
        return execute(ToolExecutionContext.anonymous(), arguments);
    }

    private String add(String userId, Map<String, String> arguments) {
        BigDecimal amount;
        try {
            amount = new BigDecimal(ToolArguments.required(arguments, "amount", "请告诉我这笔支出的金额。"));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("支出金额必须是数字。");
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("支出金额必须大于 0。");
        }
        String category = ToolArguments.text(arguments, "category");
        if (category.isEmpty()) {
            category = "其他";
        }
        String note = ToolArguments.text(arguments, "note");
        String date = normalizeDate(ToolArguments.text(arguments, "date"));
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        List<Map<String, Object>> items = store.load(FILE, userId);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("amount", amount.stripTrailingZeros().toPlainString());
        item.put("category", category);
        item.put("note", note);
        item.put("date", date);
        items.add(item);
        store.save(FILE, userId, items);
        return "已记账：" + amount.stripTrailingZeros().toPlainString() + " 元，分类：" + category
                + (note.isEmpty() ? "" : "，备注：" + note) + "，日期：" + date;
    }

    private String list(String userId, Map<String, String> arguments, boolean reportOnly) {
        YearMonth month = normalizeMonth(ToolArguments.text(arguments, "month"));
        BigDecimal total = BigDecimal.ZERO;
        Map<String, BigDecimal> categories = new LinkedHashMap<>();
        StringBuilder details = new StringBuilder();
        int count = 0;
        for (Map<String, Object> item : store.load(FILE, userId)) {
            String date = String.valueOf(item.getOrDefault("date", ""));
            if (!date.startsWith(month.toString())) {
                continue;
            }
            BigDecimal amount = new BigDecimal(String.valueOf(item.getOrDefault("amount", "0")));
            String category = String.valueOf(item.getOrDefault("category", "其他"));
            total = total.add(amount);
            categories.merge(category, amount, BigDecimal::add);
            count++;
            if (!reportOnly) {
                details.append(count).append(". ").append(date).append(" ")
                        .append(category).append(" ").append(amount.stripTrailingZeros().toPlainString()).append("元");
                String note = String.valueOf(item.getOrDefault("note", ""));
                if (!note.isBlank()) {
                    details.append("（").append(note).append("）");
                }
                details.append("\n");
            }
        }
        if (count == 0) {
            return month + " 暂无账单记录。";
        }
        StringBuilder reply = new StringBuilder(month + " 支出：" + total.stripTrailingZeros().toPlainString()
                + " 元，共 " + count + " 笔。\n");
        if (reportOnly) {
            categories.forEach((category, amount) -> reply.append("- ").append(category).append("：")
                    .append(amount.stripTrailingZeros().toPlainString()).append(" 元\n"));
        } else {
            reply.append(details);
        }
        return reply.toString().strip();
    }

    private String normalizeDate(String value) {
        LocalDate today = LocalDate.now(BEIJING_ZONE);
        if (value.isEmpty() || "今天".equals(value)) return today.toString();
        if ("昨天".equals(value)) return today.minusDays(1).toString();
        if ("前天".equals(value)) return today.minusDays(2).toString();
        try {
            return LocalDate.parse(value).toString();
        } catch (Exception e) {
            throw new IllegalArgumentException("日期请使用 yyyy-MM-dd，或说今天、昨天、前天。");
        }
    }

    private YearMonth normalizeMonth(String value) {
        YearMonth now = YearMonth.now(BEIJING_ZONE);
        if (value.isEmpty() || List.of("本月", "这个月", "当月").contains(value)) return now;
        if (List.of("上月", "上个月").contains(value)) return now.minusMonths(1);
        if (value.matches("\\d{1,2}月")) return YearMonth.of(now.getYear(), Integer.parseInt(value.replace("月", "")));
        try {
            return YearMonth.parse(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("月份请使用 yyyy-MM、本月或上个月。");
        }
    }
}
