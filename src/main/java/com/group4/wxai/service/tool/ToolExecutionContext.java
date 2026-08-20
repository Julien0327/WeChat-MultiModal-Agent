package com.group4.wxai.service.tool;

public record ToolExecutionContext(String userId) {

    public ToolExecutionContext {
        userId = userId == null || userId.isBlank() ? "anonymous" : userId.trim();
    }

    public static ToolExecutionContext anonymous() {
        return new ToolExecutionContext("anonymous");
    }
}
