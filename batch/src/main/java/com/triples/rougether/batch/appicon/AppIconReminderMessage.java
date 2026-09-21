package com.triples.rougether.batch.appicon;

import com.triples.rougether.domain.appicon.AppIconState;
import com.triples.rougether.common.i18n.Language;

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
    static String title(AppIconState state, String language) {
        String english = switch (state) {
            case MISSING_YOU -> "Your cat is waiting for you";
            case TEARY -> "I miss you…";
            case SOBBING -> "When will you be back?";
            default -> throw new IllegalArgumentException("Inactivity state required");
        };
        return lang(language).choose(title(state), english);
    }
    static String body(AppIconState state, String language) {
        String english = switch (state) {
            case MISSING_YOU -> "How was your day? Come say hello for a moment.";
            case TEARY -> "It's been quiet for a few days. Shall we try one thing together?";
            case SOBBING -> "It's okay if it's been a while. Your place is still here.";
            default -> throw new IllegalArgumentException("Inactivity state required");
        };
        return lang(language).choose(body(state), english);
    }
    private static Language lang(String language) {
        return Language.from(language);
    }
}
