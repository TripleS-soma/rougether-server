package com.triples.rougether.userapi.aiuser.service;

public enum AiUserWaitReason {
    NO_RELEVANT_POST, RECENT_PARTICIPATION, NO_NEW_TOPIC, NO_CANDIDATE,
    DAILY_LIMIT, COOLDOWN, OUTSIDE_ACTIVE_HOURS, ONBOARDING_PENDING,
    NO_ACTION_DUE, UNSPECIFIED;

    public static AiUserWaitReason parse(String value) {
        if (value == null || value.isBlank()) return UNSPECIFIED;
        try { return valueOf(value); }
        catch (IllegalArgumentException unknown) { return UNSPECIFIED; }
    }
}
