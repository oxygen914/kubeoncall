package com.kubeoncall.skill;

/** Stable command failure raised by the MySQL-backed Skill governance service. */
public class SkillGovernanceException extends RuntimeException {

    private final Code code;

    public SkillGovernanceException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public enum Code {
        INVALID,
        NOT_FOUND,
        CONFLICT,
        VERSION_CONFLICT,
        UNAVAILABLE
    }
}
