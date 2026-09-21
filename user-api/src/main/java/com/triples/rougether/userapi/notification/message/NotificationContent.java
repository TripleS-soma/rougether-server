package com.triples.rougether.userapi.notification.message;

import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.common.i18n.Language;

public record NotificationContent(NotificationType type, String title, String body,
                                  String englishTitle, String englishBody) {
    public NotificationContent(NotificationType type, String title, String body) {
        this(type, title, body, title, body);
    }

    public NotificationContent localized(String language) {
        var selected = Language.from(language);
        return new NotificationContent(type, selected.choose(title, englishTitle), selected.choose(body, englishBody));
    }
}
