package com.group4.wxai.service.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatReplyFormatterTests {

    @Test
    void removesRoleLabelsAndCommonTemplatePlaceholders() {
        String result = ChatReplyFormatter.toNaturalChatText(
                "助手：你好，<用户姓名>！这个问题直接看代码就行。"
        );

        assertEquals("你好！这个问题直接看代码就行。", result);
    }

    @Test
    void preservesUsefulCodeAndCompactParagraphs() {
        String result = ChatReplyFormatter.toNaturalChatText(
                "先看报错位置。\n\n\n\n```java\nSystem.out.println(\"ok\");\n```"
        );

        assertTrue(result.contains("```java"));
        assertTrue(result.contains("System.out.println"));
        assertTrue(result.contains("先看报错位置。\n\n```java"));
    }

    @Test
    void providesNaturalFallbackForBlankReply() {
        assertEquals("这个我刚才没组织好，你再问我一次。",
                ChatReplyFormatter.toNaturalChatText("   "));
    }
}
