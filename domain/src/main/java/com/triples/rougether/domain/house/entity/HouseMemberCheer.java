package com.triples.rougether.domain.house.entity;

import com.triples.rougether.domain.member.entity.User;
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
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 집 멤버 간 원탭 응원 1건. cheer_type 은 CheerType.code(소문자) 문자열로 저장(RoomSurfaceSlot.slot_type 컨벤션).
// UNIQUE(sender, target, cheer_type, cheer_date, daily_seq) - 같은 사람에게 같은 타입은 하루(KST) 5회(daily_seq 1..5).
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "house_member_cheers")
public class HouseMemberCheer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "house_id", nullable = false)
    private House house;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sender_user_id", nullable = false)
    private User sender;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_user_id", nullable = false)
    private User target;

    @Column(name = "cheer_type", length = 20, nullable = false)
    private String cheerType;

    @Column(name = "cheer_date", nullable = false)
    private LocalDate cheerDate;

    // 같은 (sender, target, cheer_type, cheer_date) 안의 순번(1..5). 유니크 키에 포함돼 하루 한도의 DB 방어선이 된다.
    @Column(name = "daily_seq", nullable = false)
    private int dailySeq;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    private HouseMemberCheer(House house, User sender, User target, CheerType type, LocalDate cheerDate,
                             int dailySeq) {
        this.house = house;
        this.sender = sender;
        this.target = target;
        this.cheerType = type.code();
        this.cheerDate = cheerDate;
        this.dailySeq = dailySeq;
        this.createdAt = Instant.now();
    }

    public static HouseMemberCheer send(House house, User sender, User target, CheerType type, LocalDate cheerDate,
                                        int dailySeq) {
        return new HouseMemberCheer(house, sender, target, type, cheerDate, dailySeq);
    }
}
