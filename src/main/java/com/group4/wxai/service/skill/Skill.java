package com.group4.wxai.service.skill;

import java.util.Map;

/**
 * A composite capability that orchestrates one or more tools, the LLM and
 * optionally knowledge retrieval to produce a complete deliverable.
 */
public interface Skill {

    String name();

    String description();

    default String inputJsonSchema() {
        return "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";
    }

    SkillResult execute(SkillContext context, Map<String, String> arguments);
}
