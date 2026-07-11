package com.triples.rougether.domain.notification.repository;

import com.triples.rougether.domain.notification.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
}
