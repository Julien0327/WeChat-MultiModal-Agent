package com.group4.wxai.service.skill;

public record SkillContext(String userId) {

    public SkillContext {
        userId = userId == null || userId.isBlank() ? "anonymous" : userId.trim();
    }

    public static SkillContext anonymous() {
        return new SkillContext("anonymous");
    }
}
