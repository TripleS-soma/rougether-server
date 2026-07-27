package com.triples.rougether.domain.member.repository;

import com.triples.rougether.domain.member.entity.OauthAccount;
import com.triples.rougether.domain.member.entity.OauthProvider;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OauthAccountRepository extends JpaRepository<OauthAccount, Long> {

    // 소셜 로그인 3종(카카오·구글·애플)의 계정 식별 경로. 탈퇴자는 조회되지 않아야 한다 —
    // 탈퇴가 이 행을 지우므로 정상 흐름에선 애초에 안 걸리지만, 삭제가 누락된 행이 남더라도
    // 탈퇴 계정으로 로그인되지 않도록 deleted_at 조건을 이중 방어선으로 둔다.
    @Query("select oa from OauthAccount oa join fetch oa.user u "
            + "where oa.provider = :provider and oa.providerUserId = :providerUserId and u.deletedAt is null")
    Optional<OauthAccount> findByProviderAndProviderUserId(@Param("provider") OauthProvider provider,
                                                           @Param("providerUserId") String providerUserId);

    // 초대 보상 수령 시 provider 식별자 복사용. 계정 연결(linking) 기능이 없어 user 당 최대 1건이다.
    Optional<OauthAccount> findByUserId(Long userId);

    // 탈퇴 시 연결 해제. UNIQUE(provider, provider_user_id) 때문에 행을 남기면 재로그인이
    // 탈퇴 계정으로 붙어 "재가입 = 새 계정"이 깨진다.
    void deleteByUserId(Long userId);
}
