package com.triples.rougether.domain.room.entity;

import com.triples.rougether.domain.member.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 장기 미접속 방의 거미줄 상태. room_user_id 당 행 1개를 재사용하며 cleaned_at null 이면 활성 상태임.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "room_cobwebs")
public class RoomCobweb {

    @Id
    @Column(name = "room_user_id")
    private Long roomUserId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_user_id")
    private PersonalRoom room;

    @Column(name = "appeared_at", nullable = false)
    private Instant appearedAt;

    @Column(name = "cleaned_at")
    private Instant cleanedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cleaned_by_user_id")
    private User cleanedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public boolean isActive() {
        return cleanedAt == null;
    }

    public void clean(User cleaner, Instant now) {
        if (!isActive()) {
            throw new IllegalStateException("활성 거미줄만 청소할 수 있음");
        }
        this.cleanedAt = now;
        this.cleanedBy = cleaner;
        this.updatedAt = now;
    }
}
