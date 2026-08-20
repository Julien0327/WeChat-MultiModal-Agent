package com.group4.wxai.service.tool;

import java.util.Map;

final class ToolArguments {

    private ToolArguments() {
    }

    static String text(Map<String, String> arguments, String name) {
        return arguments.getOrDefault(name, "").trim();
    }

    static String required(Map<String, String> arguments, String name, String message) {
        String value = text(arguments, name);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
