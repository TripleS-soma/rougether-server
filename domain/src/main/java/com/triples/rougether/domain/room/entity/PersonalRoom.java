package com.triples.rougether.domain.room.entity;

import com.triples.rougether.domain.member.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

// user_id가 PK이자 users 1:1 FK (자체 id 없음). @MapsId로 user.id를 그대로 PK로 씀.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "personal_rooms")
@EntityListeners(AuditingEntityListener.class)
public class PersonalRoom {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "growth_level", nullable = false)
    private int growthLevel;

    // 배치 데이터 정본 표시. SLOT_V1 = room_surface_slots, FREE_V1 = room_item_placements.
    @Enumerated(EnumType.STRING)
    @Column(name = "layout_format", length = 20, nullable = false)
    private RoomLayoutFormat layoutFormat;

    // layout 저장 API 의 낙관적 잠금 값(baseRevision 비교). 저장 성공마다 1 증가.
    @Column(name = "layout_revision", nullable = false)
    private int layoutRevision;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private PersonalRoom(User user) {
        this.user = user;
        this.growthLevel = 0;
        this.layoutFormat = RoomLayoutFormat.SLOT_V1;
        this.layoutRevision = 0;
    }

    // 첫 방문 시 lazy 생성용. growth_level 0 으로 시작(@MapsId 로 user.id 가 그대로 PK).
    public static PersonalRoom create(User user) {
        return new PersonalRoom(user);
    }

    public boolean isFreeLayout() {
        return layoutFormat == RoomLayoutFormat.FREE_V1;
    }

    // 자유배치 첫 저장 시 방 단위 지연 전환. 역방향 전환은 없다(FREE_V1 이면 유지).
    public void convertToFreeLayout() {
        if (layoutFormat == RoomLayoutFormat.SLOT_V1) {
            this.layoutFormat = RoomLayoutFormat.FREE_V1;
        }
    }

    public void increaseLayoutRevision() {
        this.layoutRevision++;
    }
}
