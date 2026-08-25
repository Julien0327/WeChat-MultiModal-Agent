package com.group4.wxai.service.capability;

import com.group4.wxai.service.skill.Skill;
import com.group4.wxai.service.skill.SkillRegistry;
import com.group4.wxai.service.skill.SkillResult;
import com.group4.wxai.service.tool.AiTool;
import com.group4.wxai.service.tool.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Unified gateway that lets the Agent discover and execute both tools and
 * skills through a single entry point, without knowing whether a capability
 * is backed by a Tool or a Skill.
 */
@Slf4j
@Component
public class CapabilityRegistry {

    private final ToolRegistry toolRegistry;
    private final SkillRegistry skillRegistry;

    public CapabilityRegistry(ToolRegistry toolRegistry, SkillRegistry skillRegistry) {
        this.toolRegistry = toolRegistry;
        this.skillRegistry = skillRegistry;
        detectNameCollisions();
    }

    public List<CapabilitySpec> listCapabilities() {
        List<CapabilitySpec> capabilities = new ArrayList<>();
        for (Map.Entry<String, AiTool> entry : toolRegistry.all().entrySet()) {
            AiTool tool = entry.getValue();
            capabilities.add(new CapabilitySpec(
                    tool.name(), tool.description(), tool.inputJsonSchema(), CapabilityKind.TOOL
            ));
        }
        for (Map.Entry<String, Skill> entry : skillRegistry.all().entrySet()) {
            Skill skill = entry.getValue();
            capabilities.add(new CapabilitySpec(
                    skill.name(), skill.description(), skill.inputJsonSchema(), CapabilityKind.SKILL
            ));
        }
        return capabilities;
    }

    public CapabilityResult execute(String capabilityName, Map<String, String> arguments) {
        return execute(capabilityName, null, arguments);
    }

    public CapabilityResult execute(String capabilityName, String userId, Map<String, String> arguments) {
        if (toolRegistry.all().containsKey(capabilityName)) {
            String output = toolRegistry.execute(capabilityName, userId, arguments);
            return CapabilityResult.success(output);
        }
        if (skillRegistry.all().containsKey(capabilityName)) {
            SkillResult result = skillRegistry.execute(capabilityName, userId, arguments);
            return new CapabilityResult(result.ok(), result.output(), result.errorMessage());
        }
        throw new IllegalArgumentException("未找到能力: " + capabilityName);
    }

    private void detectNameCollisions() {
        Set<String> overlap = new HashSet<>(toolRegistry.names());
        overlap.retainAll(skillRegistry.names());
        if (!overlap.isEmpty()) {
            throw new IllegalStateException("存在同名工具与技能，Agent 无法区分: " + overlap);
        }
    }
}
