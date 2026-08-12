package com.triples.rougether.domain.gacha.repository;

import com.triples.rougether.domain.gacha.entity.Gacha;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GachaRepository extends JpaRepository<Gacha, Long> {

    @EntityGraph(attributePaths = "theme")
    @Query("select g from Gacha g")
    List<Gacha> findAllWithTheme();

    List<Gacha> findByThemeIdAndActiveIsTrue(Long themeId);

    // 뽑기 풀 등록 경로 전용 — REPEATABLE READ 스냅샷 대신 최신 커밋을 읽도록 locking read 로 조회한다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from Gacha g where g.theme.id = :themeId and g.active = true")
    List<Gacha> findActiveByThemeIdForUpdate(@Param("themeId") Long themeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select g from Gacha g
            where g.theme.id = :themeId and g.code = :code and g.active = true
            """)
    Optional<Gacha> findActiveByThemeIdAndCodeForUpdate(@Param("themeId") Long themeId,
                                                        @Param("code") String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select g from Gacha g
            where g.theme.id = :themeId and g.code <> :excludedCode and g.active = true
            """)
    List<Gacha> findActiveByThemeIdAndCodeNotForUpdate(@Param("themeId") Long themeId,
                                                       @Param("excludedCode") String excludedCode);
}
