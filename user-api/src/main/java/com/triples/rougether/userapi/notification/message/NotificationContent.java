package com.triples.rougether.userapi.notification.message;

import com.triples.rougether.domain.notification.entity.NotificationType;

public record NotificationContent(NotificationType type, String title, String body) {
}
