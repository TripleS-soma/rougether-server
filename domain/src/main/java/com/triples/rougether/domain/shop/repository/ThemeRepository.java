package com.triples.rougether.domain.shop.repository;

import com.triples.rougether.domain.shop.entity.Theme;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ThemeRepository extends JpaRepository<Theme, Long> {

    Optional<Theme> findByCode(String code);

    // 뽑기 풀 등록 경로 전용 — 테마 행 락으로 동시 등록의 머신/엔트리 중복 생성을 막는다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Theme t where t.id = :themeId")
    Optional<Theme> findWithLockById(@Param("themeId") Long themeId);
}
