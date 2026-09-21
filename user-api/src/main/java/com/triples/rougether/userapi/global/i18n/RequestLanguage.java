package com.triples.rougether.userapi.global.i18n;

import com.triples.rougether.common.i18n.Language;
import org.springframework.context.i18n.LocaleContextHolder;

public final class RequestLanguage {
    private RequestLanguage() {}
    public static Language current() {
        return Language.from(LocaleContextHolder.getLocale().getLanguage());
    }
}
