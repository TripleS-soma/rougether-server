package com.triples.rougether.common.i18n;

public enum Language {
    KO, EN;

    public static Language from(String code) {
        return "en".equalsIgnoreCase(code) ? EN : KO;
    }

    public String choose(String korean, String english) {
        return this == EN ? english : korean;
    }
}
