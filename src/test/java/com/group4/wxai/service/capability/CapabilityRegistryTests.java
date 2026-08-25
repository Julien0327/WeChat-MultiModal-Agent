package com.group4.wxai.service.capability;

import com.group4.wxai.service.skill.Skill;
import com.group4.wxai.service.skill.SkillContext;
import com.group4.wxai.service.skill.SkillRegistry;
import com.group4.wxai.service.skill.SkillResult;
import com.group4.wxai.service.tool.AiTool;
import com.group4.wxai.service.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityRegistryTests {

    @Test
    void listsToolAndSkillCapabilitiesWithMetadata() {
        ToolRegistry toolRegistry = new ToolRegistry(List.of(tool("weather", "查询天气", "晴")));
        SkillRegistry skillRegistry = new SkillRegistry(List.of(skill("travel_route", "生成路线", SkillResult.success("路线"))));
        CapabilityRegistry registry = new CapabilityRegistry(toolRegistry, skillRegistry);

        List<CapabilitySpec> specs = registry.listCapabilities();

        assertEquals(2, specs.size());
        assertTrue(specs.stream().anyMatch(s ->
                s.kind() == CapabilityKind.TOOL && "weather".equals(s.name()) && !s.description().isBlank()));
        assertTrue(specs.stream().anyMatch(s ->
                s.kind() == CapabilityKind.SKILL && "travel_route".equals(s.name())));
    }

    @Test
    void executesRegisteredTool() {
        ToolRegistry toolRegistry = new ToolRegistry(List.of(tool("weather", "查询天气", "成都晴天")));
        SkillRegistry skillRegistry = new SkillRegistry(List.of());
        CapabilityRegistry registry = new CapabilityRegistry(toolRegistry, skillRegistry);

        CapabilityResult result = registry.execute("weather", Map.of("location", "成都"));

        assertTrue(result.ok());
        assertEquals("成都晴天", result.output());
    }

    @Test
    void executesRegisteredSkill() {
        ToolRegistry toolRegistry = new ToolRegistry(List.of());
        SkillRegistry skillRegistry = new SkillRegistry(
                List.of(skill("travel_route", "生成路线", SkillResult.success("三日游路线"))));
        CapabilityRegistry registry = new CapabilityRegistry(toolRegistry, skillRegistry);

        CapabilityResult result = registry.execute("travel_route", "user-1", Map.of("destination", "上海"));

        assertTrue(result.ok());
        assertEquals("三日游路线", result.output());
    }

    @Test
    void propagatesSkillFailureIntoResult() {
        ToolRegistry toolRegistry = new ToolRegistry(List.of());
        SkillRegistry skillRegistry = new SkillRegistry(
                List.of(skill("travel_route", "生成路线", SkillResult.failure("缺少目的地"))));
        CapabilityRegistry registry = new CapabilityRegistry(toolRegistry, skillRegistry);

        CapabilityResult result = registry.execute("travel_route", Map.of());

        assertTrue(!result.ok());
        assertEquals("缺少目的地", result.errorMessage());
    }

    @Test
    void throwsWhenCapabilityNotFound() {
        CapabilityRegistry registry = new CapabilityRegistry(
                new ToolRegistry(List.of()), new SkillRegistry(List.of()));

        assertThrows(IllegalArgumentException.class, () -> registry.execute("missing", Map.of()));
    }

    @Test
    void rejectsNameCollisionBetweenToolAndSkill() {
        ToolRegistry toolRegistry = new ToolRegistry(List.of(tool("travel", "工具", "tool-output")));
        SkillRegistry skillRegistry = new SkillRegistry(
                List.of(skill("travel", "技能", SkillResult.success("skill-output"))));

        assertThrows(IllegalStateException.class, () -> new CapabilityRegistry(toolRegistry, skillRegistry));
    }

    private static AiTool tool(String name, String description, String output) {
        return new AiTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public String execute(Map<String, String> arguments) {
                return output;
            }
        };
    }

    private static Skill skill(String name, String description, SkillResult result) {
        return new Skill() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public SkillResult execute(SkillContext context, Map<String, String> arguments) {
                return result;
            }
        };
    }
}
