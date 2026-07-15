package com.triples.rougether.userapi.notification.service;

import com.triples.rougether.domain.notification.entity.Notification;
import com.triples.rougether.domain.notification.repository.NotificationRepository;
import com.triples.rougether.userapi.notification.dto.NotificationListResponse;
import com.triples.rougether.userapi.notification.dto.NotificationListResponse.NotificationItem;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationQueryService {

    private final NotificationRepository notificationRepository;

    public NotificationListResponse getNotifications(Long userId, Long cursor, int size) {
        List<Notification> found = notificationRepository.findPageByCursor(
                userId, cursor, PageRequest.of(0, size + 1));
        boolean hasNext = found.size() > size;
        List<Notification> page = hasNext ? found.subList(0, size) : found;
        List<NotificationItem> items = page.stream().map(NotificationItem::of).toList();
        Long nextCursor = hasNext ? page.get(page.size() - 1).getId() : null;
        return new NotificationListResponse(items, nextCursor, hasNext);
    }
}
