package com.triples.rougether.batch.eveningdigest;

import com.triples.rougether.common.i18n.Language;
final class EveningDigestMessage {

    static final String TITLE = "오늘의 루틴을 마무리해 볼까요?";

    private EveningDigestMessage() {
    }

    static String body(int routineCount, int todoCount) {
        int totalCount = routineCount + todoCount;
        return "오늘 아직 %d개가 남았어요. 루틴 %d개 · 투두 %d개를 마무리해볼까요?"
                .formatted(totalCount, routineCount, todoCount);
    }
    static String title(String language) { return lang(language).choose(TITLE, "Ready to wrap up today?"); }
    static String body(int routines, int todos, String language) {
        return lang(language).choose(body(routines, todos),
                "You have %d left today: %d routines and %d to-dos. Let's finish up!".formatted(routines + todos, routines, todos));
    }
    private static Language lang(String language) {
        return Language.from(language);
    }
}
