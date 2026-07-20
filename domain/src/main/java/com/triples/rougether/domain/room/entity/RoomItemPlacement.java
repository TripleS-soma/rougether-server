package com.triples.rougether.domain.room.entity;

import com.triples.rougether.domain.shop.entity.UserItem;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 자유배치 가구 1개의 배치 상태. 좌표는 방 렌더 영역 전체 기준 0.0~1.0 정규화.
// UNIQUE(room_user_id, user_item_id) + user_items 의 UNIQUE(user_id, item_id)(V8) 조합으로
// 같은 가구(item)는 방에 1개만 배치된다. 다중 배치 허용은 spec 미결정(#162 후속) — 필요 시 이 unique 완화로 대응.
// 저장은 전체 교체(delete 후 insert) 방식이라 개별 수정 메서드 없이 place 팩토리만 둔다.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "room_item_placements")
public class RoomItemPlacement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_user_id", referencedColumnName = "user_id", nullable = false)
    private PersonalRoom room;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_item_id", nullable = false)
    private UserItem userItem;

    @Column(name = "position_x", nullable = false)
    private BigDecimal positionX;

    @Column(name = "position_y", nullable = false)
    private BigDecimal positionY;

    @Column(name = "z_index", nullable = false)
    private int zIndex;

    @Column(name = "scale", nullable = false)
    private BigDecimal scale;

    @Column(name = "rotation_deg", nullable = false)
    private int rotationDeg;

    @Column(name = "flipped", nullable = false)
    private boolean flipped;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private RoomItemPlacement(PersonalRoom room, UserItem userItem, BigDecimal positionX, BigDecimal positionY,
                              int zIndex, BigDecimal scale, int rotationDeg, boolean flipped) {
        this.room = room;
        this.userItem = userItem;
        this.positionX = positionX;
        this.positionY = positionY;
        this.zIndex = zIndex;
        this.scale = scale;
        this.rotationDeg = rotationDeg;
        this.flipped = flipped;
        this.updatedAt = Instant.now();
    }

    public static RoomItemPlacement place(PersonalRoom room, UserItem userItem, BigDecimal positionX,
                                          BigDecimal positionY, int zIndex, BigDecimal scale,
                                          int rotationDeg, boolean flipped) {
        return new RoomItemPlacement(room, userItem, positionX, positionY, zIndex, scale, rotationDeg, flipped);
    }
}
