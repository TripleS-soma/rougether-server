package com.triples.rougether.domain.house.entity;

import com.triples.rougether.domain.member.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "house_members")
public class HouseMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "house_id", nullable = false)
    private House house;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 30, nullable = false)
    private HouseMemberRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30, nullable = false)
    private HouseMemberStatus status;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    @Column(name = "left_at")
    private Instant leftAt;

    // 구성원 등록. 참여는 즉시가입 정책이라 항상 ACTIVE 로 시작.
    public static HouseMember create(House house, User user, HouseMemberRole role) {
        HouseMember member = new HouseMember();
        member.house = house;
        member.user = user;
        member.role = role;
        member.status = HouseMemberStatus.ACTIVE;
        member.joinedAt = Instant.now();
        return member;
    }

    // LEFT 이력 재가입 - uq_house_member(house_id,user_id) 제약상 새 row 대신 기존 row 를 재활성화한다.
    public void reactivate() {
        this.status = HouseMemberStatus.ACTIVE;
        this.joinedAt = Instant.now();
        this.leftAt = null;
    }

    public boolean isActive() {
        return status == HouseMemberStatus.ACTIVE;
    }

    // 탈퇴 - LEFT 전환 + left_at 기록. 기여 기록은 유지되고 재가입 시 reactivate 로 되돌린다.
    public void leave() {
        this.status = HouseMemberStatus.LEFT;
        this.leftAt = Instant.now();
    }

    // 소유권 양도 - 반드시 기존 소유자 demote 와 한 트랜잭션으로 묶는다(소유자 2명 방지).
    public void promoteToOwner() {
        this.role = HouseMemberRole.OWNER;
    }

    public void demoteToMember() {
        this.role = HouseMemberRole.MEMBER;
    }

    public boolean isOwner() {
        return role == HouseMemberRole.OWNER;
    }
}
