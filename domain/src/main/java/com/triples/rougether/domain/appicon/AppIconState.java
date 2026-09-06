package com.triples.rougether.domain.appicon;

public enum AppIconState {
    NORMAL("왔냥? 기다리고 있었다냥!", 0),
    MISSING_YOU("요즘 좀 뜸하다냥…", 1),
    TEARY("보고 싶다냥…", 2),
    SOBBING("언제 돌아오냥… 기다리고 있다냥.", 3),
    DAILY_SUCCESS("오늘도 해냈다냥!", 0),
    STREAK_CHAMPION("꾸준함의 왕이다냥!", 0);

    private final String message;
    private final int inactivityStage;

    AppIconState(String message, int inactivityStage) {
        this.message = message;
        this.inactivityStage = inactivityStage;
    }

    public String message() {
        return message;
    }

    public int inactivityStage() {
        return inactivityStage;
    }
}
