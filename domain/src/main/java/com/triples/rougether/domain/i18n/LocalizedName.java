package com.triples.rougether.domain.i18n;

import java.util.Map;

public interface LocalizedName {
    String getName();
    Map<String, String> getNameTranslations();
    void updateNameTranslations(Map<String, String> translations);

    default String nameIn(String language) {
        var translations = getNameTranslations();
        if ("ko".equals(language) || translations == null) { return getName(); }
        String translated = translations.get(language);
        return translated == null || translated.isBlank() ? getName() : translated;
    }
}
