package com.triples.rougether.batch.appicon;

import com.triples.rougether.domain.appicon.AppIconState;

final class AppIconReminderMessage {

    private AppIconReminderMessage() {
    }

    static String title(AppIconState state) {
        return switch (state) {
            case MISSING_YOU -> "고양이가 기다린다냥";
            case TEARY -> "보고 싶다냥…";
            case SOBBING -> "언제 돌아오냥…";
            default -> throw new IllegalArgumentException("미접속 상태만 알림을 보낼 수 있음");
        };
    }

    static String body(AppIconState state) {
        return switch (state) {
            case MISSING_YOU -> "오늘은 어떤 하루였냥? 잠깐 얼굴 보러 와주라냥.";
            case TEARY -> "며칠째 조용해서 조금 심심하다냥. 같이 하나만 해볼까냥?";
            case SOBBING -> "오랜만이어도 괜찮다냥. 네 자리는 그대로 있다냥.";
            default -> throw new IllegalArgumentException("미접속 상태만 알림을 보낼 수 있음");
        };
    }
}
