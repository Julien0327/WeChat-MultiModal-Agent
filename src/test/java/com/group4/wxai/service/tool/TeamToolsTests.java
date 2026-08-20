package com.group4.wxai.service.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamToolsTests {

    @TempDir
    Path temporaryDirectory;

    @Test
    void calculatorUsesDecimalArithmeticAndRejectsZeroDivisor() {
        CalculatorTool tool = new CalculatorTool();

        assertEquals("12.5 × 4 = 50", tool.execute(Map.of(
                "a", "12.5", "b", "4", "operation", "multiply"
        )));
        assertThrows(IllegalArgumentException.class, () -> tool.execute(Map.of(
                "a", "1", "b", "0", "operation", "divide"
        )));
    }

    @Test
    void todoDataIsPersistentAndIsolatedByWechatUser() {
        TodoTool tool = new TodoTool(store());
        ToolExecutionContext firstUser = new ToolExecutionContext("user-1");
        ToolExecutionContext secondUser = new ToolExecutionContext("user-2");

        String created = tool.execute(firstUser, Map.of(
                "action", "create", "title", "交项目报告", "due", "明天下午"
        ));

        assertTrue(created.contains("交项目报告"));
        assertTrue(tool.execute(firstUser, Map.of("action", "list")).contains("交项目报告"));
        assertEquals("没有符合条件的待办事项。",
                tool.execute(secondUser, Map.of("action", "list")));
    }

    @Test
    void expenseReportUsesOnlyStoredUserData() {
        ExpenseTool tool = new ExpenseTool(store());
        ToolExecutionContext firstUser = new ToolExecutionContext("user-1");
        ToolExecutionContext secondUser = new ToolExecutionContext("user-2");

        tool.execute(firstUser, Map.of(
                "action", "add", "amount", "25.50", "category", "餐饮", "note", "午饭"
        ));

        String report = tool.execute(firstUser, Map.of("action", "report", "month", "本月"));
        assertTrue(report.contains("25.5 元"));
        assertTrue(report.contains("餐饮"));
        assertTrue(tool.execute(secondUser, Map.of("action", "report", "month", "本月"))
                .contains("暂无账单记录"));
    }

    @Test
    void reminderRequiresBothTimeAndContentAndPersistsTheResult() {
        ReminderTool tool = new ReminderTool(store());
        ToolExecutionContext context = new ToolExecutionContext("user-1");

        assertThrows(IllegalArgumentException.class,
                () -> tool.execute(context, Map.of("action", "set", "time", "10分钟后")));
        String result = tool.execute(context, Map.of(
                "action", "set", "time", "10分钟后", "content", "喝水", "repeat", "once"
        ));

        assertTrue(result.contains("喝水"));
        assertTrue(tool.execute(context, Map.of("action", "list")).contains("喝水"));
    }

    @Test
    void externallyBackedToolsExplainMissingConfiguration() {
        HistoryTodayTool history = new HistoryTodayTool(new OkHttpClient(), new ObjectMapper());
        JokeTool joke = new JokeTool(new OkHttpClient(), new ObjectMapper());
        ExpressTrackingTool express = new ExpressTrackingTool(new OkHttpClient(), new ObjectMapper());
        ReflectionTestUtils.setField(history, "apiUrl", "");
        ReflectionTestUtils.setField(history, "apiKey", "");
        ReflectionTestUtils.setField(joke, "apiUrl", "");
        ReflectionTestUtils.setField(joke, "apiKey", "");
        ReflectionTestUtils.setField(express, "customer", "");
        ReflectionTestUtils.setField(express, "apiKey", "");

        assertTrue(assertThrows(IllegalStateException.class,
                () -> history.execute(Map.of())).getMessage().contains("HISTORY_API"));
        assertTrue(assertThrows(IllegalStateException.class,
                () -> joke.execute(Map.of())).getMessage().contains("JOKE_API"));
        assertTrue(assertThrows(IllegalStateException.class,
                () -> express.execute(Map.of("company", "SF", "tracking_no", "123")))
                .getMessage().contains("EXPRESS_"));
    }

    private JsonDataStore store() {
        return new JsonDataStore(new ObjectMapper(), temporaryDirectory.toString());
    }
}
