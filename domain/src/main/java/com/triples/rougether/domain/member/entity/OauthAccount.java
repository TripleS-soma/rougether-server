package com.triples.rougether.domain.member.entity;

import com.triples.rougether.domain.support.BaseCreatedEntity;
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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "oauth_accounts")
public class OauthAccount extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", length = 20, nullable = false)
    private OauthProvider provider;

    @Column(name = "provider_user_id", length = 255, nullable = false)
    private String providerUserId;

    // 탈퇴 시 애플 revoke 호출용 refresh token(암호화 저장). provider=APPLE일 때만 채워짐.
    @Column(name = "apple_refresh_token_encrypted", length = 1000)
    private String appleRefreshTokenEncrypted;

    private OauthAccount(User user, OauthProvider provider, String providerUserId) {
        this.user = user;
        this.provider = provider;
        this.providerUserId = providerUserId;
    }

    public static OauthAccount link(User user, OauthProvider provider, String providerUserId) {
        return new OauthAccount(user, provider, providerUserId);
    }

    // 애플 로그인 성공 시마다 최신 refresh token으로 갱신함.
    public void updateAppleRefreshToken(String encrypted) {
        this.appleRefreshTokenEncrypted = encrypted;
    }
}
