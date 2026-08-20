package com.group4.wxai.service.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class ToolRegistry {

    private final Map<String, AiTool> tools;

    public ToolRegistry(List<AiTool> toolList) {
        Map<String, AiTool> registered = new LinkedHashMap<>();
        for (AiTool tool : toolList) {
            AiTool previous = registered.putIfAbsent(tool.name(), tool);
            if (previous != null) {
                throw new IllegalStateException("存在重复工具名称: " + tool.name());
            }
        }
        this.tools = Collections.unmodifiableMap(registered);
        log.info("AI工具注册完成: {}", tools.keySet());
    }

    public String execute(String toolName, Map<String, String> arguments) {
        AiTool tool = requireTool(toolName);
        Map<String, String> safeArguments = safeArguments(arguments);
        log.info("执行AI工具: name={}, argumentKeys={}", toolName, safeArguments.keySet());
        return tool.execute(safeArguments);
    }

    public String execute(String toolName, String userId, Map<String, String> arguments) {
        return execute(toolName, new ToolExecutionContext(userId), arguments);
    }

    private String execute(String toolName, ToolExecutionContext context, Map<String, String> arguments) {
        AiTool tool = requireTool(toolName);
        Map<String, String> safeArguments = safeArguments(arguments);
        log.info("执行AI工具: name={}, argumentKeys={}", toolName, safeArguments.keySet());
        return tool.execute(context, safeArguments);
    }

    private AiTool requireTool(String toolName) {
        AiTool tool = tools.get(toolName);
        if (tool == null) {
            throw new IllegalArgumentException("未找到工具: " + toolName);
        }
        return tool;
    }

    private Map<String, String> safeArguments(Map<String, String> arguments) {
        return arguments == null ? Map.of() : Map.copyOf(arguments);
    }

    public Set<String> names() {
        return tools.keySet();
    }
}
