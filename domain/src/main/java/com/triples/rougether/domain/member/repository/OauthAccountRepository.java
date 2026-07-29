package com.triples.rougether.domain.member.repository;

import com.triples.rougether.domain.member.entity.OauthAccount;
import com.triples.rougether.domain.member.entity.OauthProvider;
import com.triples.rougether.domain.member.entity.User;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OauthAccountRepository extends JpaRepository<OauthAccount, Long> {

    Optional<OauthAccount> findByProviderAndProviderUserId(OauthProvider provider, String providerUserId);

    List<OauthAccount> findAllByUser(User user);

    // 탈퇴 시 연동 삭제 → (provider, provider_user_id) unique가 풀려 재로그인이 신규 가입으로 동작함.
    void deleteAllByUser(User user);
}
