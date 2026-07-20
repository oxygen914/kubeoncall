package com.kubeoncall.web.dto;

import java.util.List;

import com.kubeoncall.skill.SkillIndexEntry;

public record SkillListResponse(List<SkillIndexEntry> skills, List<String> loadErrors) {
    public SkillListResponse {
        skills = skills == null ? List.of() : List.copyOf(skills);
        loadErrors = loadErrors == null ? List.of() : List.copyOf(loadErrors);
    }
}
