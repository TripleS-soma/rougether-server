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
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "room_surface_slots")
public class RoomSurfaceSlot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_user_id", referencedColumnName = "user_id", nullable = false)
    private PersonalRoom room;

    @Column(name = "slot_type", length = 40, nullable = false)
    private String slotType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_item_id")
    private UserItem userItem;

    @Column(name = "saved_at", nullable = false)
    private Instant savedAt;
}
