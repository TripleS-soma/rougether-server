package com.triples.rougether.batch.reminder;

final class ReminderMessage {

    static final String TITLE = "루틴 리마인드";
    static final String TODO_TITLE = "투두 리마인드";

    private ReminderMessage() {
    }

    static String body(String routineTitle) {
        return "『" + routineTitle + "』 할 시간이에요!";
    }

    static String todoBody(String todoTitle) {
        return "『" + todoTitle + "』 마감 시간이에요!";
    }
}
