package com.triples.rougether.batch.reminder;

import com.triples.rougether.common.i18n.Language;
final class ReminderMessage {

    static final String TITLE = "루틴 리마인드";
    static final String TODO_TITLE = "투두 리마인드";

    private ReminderMessage() {
    }

    static String body(String routineTitle) {
        return "『" + routineTitle + "』 할 시간이에요!";
    }

    static String todoBody(String todoTitle) {
        return "『" + todoTitle + "』 할 시간이에요!";
    }
    static String title(String language) { return lang(language).choose(TITLE, "Routine reminder"); }
    static String todoTitle(String language) { return lang(language).choose(TODO_TITLE, "To-do reminder"); }
    static String body(String title, String language) {
        return lang(language).choose(body(title), "Time for “" + title + "”!");
    }
    static String todoBody(String title, String language) { return body(title, language); }
    private static Language lang(String language) {
        return Language.from(language);
    }
}
