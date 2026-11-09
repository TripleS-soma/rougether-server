package com.triples.rougether.domain.christmas.entity;

import com.triples.rougether.domain.member.entity.User;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "event_popup_dismissals", uniqueConstraints = @UniqueConstraint(
        name = "uq_event_popup_dismissal_user_code", columnNames = {"user_id", "event_code"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventPopupDismissal {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    @Column(name = "event_code", nullable = false, length = 50)
    private String eventCode;
    @Column(name = "dismissed_at", nullable = false)
    private Instant dismissedAt;

    public EventPopupDismissal(User user, String eventCode, Instant dismissedAt) {
        this.user = user;
        this.eventCode = eventCode;
        this.dismissedAt = dismissedAt;
    }
}
