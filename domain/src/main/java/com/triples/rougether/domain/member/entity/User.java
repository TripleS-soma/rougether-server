package com.triples.rougether.domain.member.entity;

import com.triples.rougether.domain.support.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "users")
public class User extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nickname", length = 30)
    private String nickname;

    @Column(name = "bio", length = 100)
    private String bio;

    // S3 object key (profile/{uuid}.{ext}). null = 프론트가 기본 이미지 표시.
    @Column(name = "profile_image_key", length = 255)
    private String profileImageKey;

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "last_accessed_at")
    private Instant lastAccessedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    private User(String email) {
        this.email = email;
    }

    // 소셜 가입 시점엔 닉네임을 받지 않음(온보딩에서 채움) → nickname null.
    public static User signUp() {
        return new User();
    }

    // 소셜 provider가 이메일을 제공/동의한 경우 가입 시 저장함(미제공이면 null).
    public static User signUp(String email) {
        return new User(email);
    }

    // 마지막 접속(토큰 발급) 시각 기록. 로그인과 refresh 정상 회전 시 갱신됨.
    public void recordAccess(Instant now) {
        this.lastAccessedAt = now;
    }

    public void changeNickname(String nickname) {
        this.nickname = nickname;
    }

    public void changeBio(String bio) {
        this.bio = bio;
    }

    public void changeProfileImage(String profileImageKey) {
        this.profileImageKey = profileImageKey;
    }

    public void removeProfileImage() {
        this.profileImageKey = null;
    }
}
