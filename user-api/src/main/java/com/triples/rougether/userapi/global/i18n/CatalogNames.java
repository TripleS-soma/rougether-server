package com.triples.rougether.userapi.global.i18n;

import com.triples.rougether.domain.i18n.LocalizedName;

public final class CatalogNames {
    private CatalogNames() {}
    public static String name(LocalizedName value) {
        var translations = value.getNameTranslations();
        if (RequestLanguage.current() != com.triples.rougether.common.i18n.Language.EN || translations == null) {
            return value.getName();
        }
        String english = translations.get("en");
        return english == null || english.isBlank() ? value.getName() : english;
    }
}
