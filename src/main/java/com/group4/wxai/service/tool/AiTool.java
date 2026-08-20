package com.group4.wxai.service.tool;

import java.util.Map;

/**
 * A deterministic capability that can be selected by the intent model and
 * executed by application code.
 */
public interface AiTool {

    String name();

    String description();

    default String inputJsonSchema() {
        return "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";
    }

    String execute(Map<String, String> arguments);
}
