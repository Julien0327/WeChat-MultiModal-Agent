package com.group4.wxai.service.tool;

import com.group4.wxai.service.llm.LlmService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class TranslateTool implements AiTool {

    public static final String NAME = "translate";
    private final LlmService llmService;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "把用户明确给出的文本翻译到指定语言；不补写原文，不改变事实。";
    }

    @Override
    public String inputJsonSchema() {
        return """
                {"type":"object","properties":{"text":{"type":"string"},
                "target_language":{"type":"string"}},"required":["text","target_language"],
                "additionalProperties":false}
                """;
    }

    @Override
    public String execute(ToolExecutionContext context, Map<String, String> arguments) {
        String text = arguments.getOrDefault("text", "").trim();
        String language = arguments.getOrDefault("target_language", "").trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("请告诉我要翻译的原文。");
        }
        if (language.isEmpty()) {
            throw new IllegalArgumentException("请告诉我要翻译成哪种语言。");
        }
        String prompt = "把下面文本翻译成" + language + "。只输出译文，不解释，不扩写：\n" + text;
        return llmService.chatText(context.userId() + ":translate", prompt, List.of());
    }

    @Override
    public String execute(Map<String, String> arguments) {
        return execute(ToolExecutionContext.anonymous(), arguments);
    }
}
