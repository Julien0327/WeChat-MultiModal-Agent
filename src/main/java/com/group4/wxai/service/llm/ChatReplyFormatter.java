package com.group4.wxai.service.llm;

import java.util.regex.Pattern;

/**
 * Keeps model replies suitable for a normal one-to-one chat while preserving
 * useful content such as code blocks and numbered steps.
 */
final class ChatReplyFormatter {

    private static final Pattern ROLE_PREFIX = Pattern.compile(
            "^(?i:assistant|ai|助手|机器人|回复|回答)\\s*[:：]\\s*"
    );
    private static final String PLACEHOLDER_NAME =
            "(?i:user(?:_?name)?|name|nickname|username|用户名|用户姓名|姓名|昵称|占位符)";
    private static final Pattern TEMPLATE_PLACEHOLDER = Pattern.compile(
            "(?:<\\s*" + PLACEHOLDER_NAME + "\\s*>"
                    + "|\\[\\s*" + PLACEHOLDER_NAME + "\\s*]"
                    + "|\\{\\{?\\s*" + PLACEHOLDER_NAME + "\\s*}*"
                    + "|\\$\\{\\s*" + PLACEHOLDER_NAME + "\\s*})"
    );
    private static final Pattern EXCESSIVE_BLANK_LINES = Pattern.compile("(?:\\R[ \\t]*){3,}");

    private ChatReplyFormatter() {
    }

    static String toNaturalChatText(String content) {
        if (content == null || content.isBlank()) {
            return "这个我刚才没组织好，你再问我一次。";
        }
        String result = ROLE_PREFIX.matcher(content.trim()).replaceFirst("");
        result = TEMPLATE_PLACEHOLDER.matcher(result).replaceAll("");
        result = result.replaceAll("[ \\t]+([，。！？；：,.!?;:])", "$1");
        result = result.replaceAll("([，,])\\s*([。！？!?])", "$2");
        result = EXCESSIVE_BLANK_LINES.matcher(result).replaceAll("\n\n");
        result = result.trim();
        return result.isBlank() ? "这个我刚才没组织好，你再问我一次。" : result;
    }
}
