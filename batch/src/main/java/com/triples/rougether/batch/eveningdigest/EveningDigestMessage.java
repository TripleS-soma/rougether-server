package com.triples.rougether.batch.eveningdigest;

final class EveningDigestMessage {

    static final String TITLE = "오늘의 루틴을 마무리해 볼까요?";

    private EveningDigestMessage() {
    }

    static String body(int routineCount, int todoCount) {
        int totalCount = routineCount + todoCount;
        return "오늘 아직 %d개가 남았어요. 루틴 %d개 · 투두 %d개를 마무리해볼까요?"
                .formatted(totalCount, routineCount, todoCount);
    }
}
