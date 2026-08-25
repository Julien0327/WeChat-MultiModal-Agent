package com.group4.wxai.service.skill;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillRegistryTests {

    @Test
    void registersAndExecutesNamedSkills() {
        Skill route = skill("travel_route", "生成旅游路线", SkillResult.success("路线已生成"));
        SkillRegistry registry = new SkillRegistry(List.of(route));

        assertTrue(registry.names().contains("travel_route"));

        SkillResult result = registry.execute("travel_route", Map.of("destination", "上海"));
        assertTrue(result.ok());
        assertEquals("路线已生成", result.output());
    }

    @Test
    void normalizesMissingUserIdToAnonymous() {
        SkillRegistry registry = new SkillRegistry(List.of(skill("s", "d", SkillResult.success("ok"))));

        SkillResult result = registry.execute("s", (String) null, Map.of());
        assertTrue(result.ok());
    }

    @Test
    void rejectsDuplicateSkillNames() {
        Skill first = skill("dup", "第一个", SkillResult.success("a"));
        Skill second = skill("dup", "第二个", SkillResult.success("b"));

        assertThrows(IllegalStateException.class, () -> new SkillRegistry(List.of(first, second)));
    }

    @Test
    void throwsWhenSkillNotFound() {
        SkillRegistry registry = new SkillRegistry(List.of());

        assertThrows(IllegalArgumentException.class, () -> registry.execute("missing", Map.of()));
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
