package com.triples.rougether.domain.house.entity;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.support.BaseEntity;
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
@Table(name = "house")
public class House extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_user_id", nullable = false)
    private User owner;

    @Column(name = "name", length = 120, nullable = false)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "cover_image_key", length = 255)
    private String coverImageKey;

    @Column(name = "max_members")
    private Integer maxMembers;

    @Column(name = "current_member_count", nullable = false)
    private int currentMemberCount;

    @Column(name = "level", nullable = false)
    private int level;

    @Column(name = "growth_points", nullable = false)
    private int growthPoints;

    @Column(name = "invite_code", length = 50)
    private String inviteCode;

    @Column(name = "invite_expires_at")
    private Instant inviteExpiresAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    // 집 생성. 생성 직후 구성원은 owner 1명, 레벨·성장포인트는 0에서 시작(개인 방 growthLevel 과 동일 기준).
    public static House create(User owner, String name, String description, String coverImageKey,
                               Integer maxMembers, String inviteCode, Instant inviteExpiresAt) {
        House house = new House();
        house.owner = owner;
        house.name = name;
        house.description = description;
        house.coverImageKey = coverImageKey;
        house.maxMembers = maxMembers;
        house.currentMemberCount = 1;
        house.level = 0;
        house.growthPoints = 0;
        house.inviteCode = inviteCode;
        house.inviteExpiresAt = inviteExpiresAt;
        return house;
    }

    // 초대코드 재발급 - 새 코드로 교체하면 기존 코드는 즉시 무효가 된다(invite_code 단일 컬럼).
    public void updateInviteCode(String inviteCode, Instant inviteExpiresAt) {
        this.inviteCode = inviteCode;
        this.inviteExpiresAt = inviteExpiresAt;
    }

    // 참여 확정 시 구성원 수 증가. 정원 검사와 같은 트랜잭션(행 락) 안에서 호출한다.
    public void increaseMemberCount() {
        this.currentMemberCount++;
    }

    // 만료 시각이 없으면(발급 이력 없음) 만료로 취급.
    public boolean isInviteExpired() {
        return inviteExpiresAt == null || inviteExpiresAt.isBefore(Instant.now());
    }

    // max_members 가 null 이면 무제한.
    public boolean isFull() {
        return maxMembers != null && currentMemberCount >= maxMembers;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
