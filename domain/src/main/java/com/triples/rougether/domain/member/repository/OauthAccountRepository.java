package com.triples.rougether.domain.member.repository;

import com.triples.rougether.domain.member.entity.OauthAccount;
import com.triples.rougether.domain.member.entity.OauthProvider;
import com.triples.rougether.domain.member.entity.User;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OauthAccountRepository extends JpaRepository<OauthAccount, Long> {

    Optional<OauthAccount> findByProviderAndProviderUserId(OauthProvider provider, String providerUserId);

    List<OauthAccount> findAllByUser(User user);

    // 같은 이메일(대소문자 무시)로 가입된 활성(미탈퇴·비봇) 회원의 소셜 provider 목록 —
    // 소셜 최초 가입 시 "이 이메일은 OO 로그인으로 가입되어 있어요" 안내(409)용. 로그인 빈도가 낮아 lower() 비교로 충분함.
    @Query("select distinct o.provider from OauthAccount o join o.user u "
            + "where lower(u.email) = lower(:email) and u.deletedAt is null and u.bot = false")
    List<OauthProvider> findProvidersOfActiveUsersByEmail(@Param("email") String email);

    // 탈퇴 시 연동 삭제 → (provider, provider_user_id) unique가 풀려 재로그인이 신규 가입으로 동작함.
    void deleteAllByUser(User user);
}
