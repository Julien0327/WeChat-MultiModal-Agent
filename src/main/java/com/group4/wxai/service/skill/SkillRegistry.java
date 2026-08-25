package com.group4.wxai.service.skill;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class SkillRegistry {

    private final Map<String, Skill> skills;

    public SkillRegistry(List<Skill> skillList) {
        Map<String, Skill> registered = new LinkedHashMap<>();
        for (Skill skill : skillList) {
            Skill previous = registered.putIfAbsent(skill.name(), skill);
            if (previous != null) {
                throw new IllegalStateException("存在重复技能名称: " + skill.name());
            }
        }
        this.skills = Collections.unmodifiableMap(registered);
        log.info("技能注册完成: {}", skills.keySet());
    }

    public SkillResult execute(String skillName, Map<String, String> arguments) {
        return execute(skillName, SkillContext.anonymous(), arguments);
    }

    public SkillResult execute(String skillName, String userId, Map<String, String> arguments) {
        return execute(skillName, new SkillContext(userId), arguments);
    }

    private SkillResult execute(String skillName, SkillContext context, Map<String, String> arguments) {
        Skill skill = requireSkill(skillName);
        Map<String, String> safeArguments = safeArguments(arguments);
        log.info("执行技能: name={}, argumentKeys={}", skillName, safeArguments.keySet());
        return skill.execute(context, safeArguments);
    }

    private Skill requireSkill(String skillName) {
        Skill skill = skills.get(skillName);
        if (skill == null) {
            throw new IllegalArgumentException("未找到技能: " + skillName);
        }
        return skill;
    }

    private Map<String, String> safeArguments(Map<String, String> arguments) {
        return arguments == null ? Map.of() : Map.copyOf(arguments);
    }

    public Set<String> names() {
        return skills.keySet();
    }

    public Map<String, Skill> all() {
        return skills;
    }
}
