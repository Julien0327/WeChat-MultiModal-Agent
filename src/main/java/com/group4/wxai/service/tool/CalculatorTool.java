package com.group4.wxai.service.tool;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Map;

@Component
public class CalculatorTool implements AiTool {

    public static final String NAME = "calculator";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "执行一次加减乘除基础计算；两个操作数和运算类型都必须由用户明确提供。";
    }

    @Override
    public String inputJsonSchema() {
        return """
                {"type":"object","properties":{"a":{"type":"number"},"b":{"type":"number"},
                "operation":{"type":"string","enum":["add","subtract","multiply","divide"]}},
                "required":["a","b","operation"],"additionalProperties":false}
                """;
    }

    @Override
    public String execute(Map<String, String> arguments) {
        BigDecimal a = number(arguments, "a");
        BigDecimal b = number(arguments, "b");
        String operation = required(arguments, "operation");
        BigDecimal result = switch (operation) {
            case "add" -> a.add(b);
            case "subtract" -> a.subtract(b);
            case "multiply" -> a.multiply(b);
            case "divide" -> {
                if (b.compareTo(BigDecimal.ZERO) == 0) {
                    throw new IllegalArgumentException("除数不能为 0。");
                }
                yield a.divide(b, MathContext.DECIMAL64);
            }
            default -> throw new IllegalArgumentException("operation 只能是 add、subtract、multiply 或 divide。");
        };
        return format(a) + " " + symbol(operation) + " " + format(b) + " = " + format(result);
    }

    private BigDecimal number(Map<String, String> arguments, String name) {
        try {
            return new BigDecimal(required(arguments, name));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " 必须是数字。");
        }
    }

    private String required(Map<String, String> arguments, String name) {
        String value = arguments.getOrDefault(name, "").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("缺少计算参数 " + name + "。");
        }
        return value;
    }

    private String format(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private String symbol(String operation) {
        return switch (operation) {
            case "add" -> "+";
            case "subtract" -> "-";
            case "multiply" -> "×";
            default -> "÷";
        };
    }
}
