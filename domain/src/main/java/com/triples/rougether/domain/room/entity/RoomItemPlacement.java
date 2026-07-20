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
// UNIQUE(room_user_id, user_item_id) — 같은 가구를 여러 개 두려면 사본(user_items row)을 여러 개 소유한다.
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
