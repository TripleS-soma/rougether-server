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
}
