package com.triples.rougether.domain.character.repository;

import com.triples.rougether.domain.character.entity.UserCharacter;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserCharacterRepository extends JpaRepository<UserCharacter, Long> {

    List<UserCharacter> findByUserId(Long userId);

    List<UserCharacter> findByUserIdAndDeletedAtIsNull(Long userId);

    Optional<UserCharacter> findByUserIdAndCharacterIdAndDeletedAtIsNull(Long userId, Long characterId);

    Optional<UserCharacter> findByUserIdAndSelectedTrueAndDeletedAtIsNull(Long userId);

    // 착용(대표) 캐릭터. 단일 착용(is_selected 는 동시에 1개만 true).
    Optional<UserCharacter> findByUserIdAndSelectedIsTrueAndDeletedAtIsNull(Long userId);

    // 보유 캐릭터 목록용: character 를 fetch join 해 N+1 회피. 마스터 정렬(sort_order) 순.
    // 회수(비활성) 캐릭터는 에셋이 내려가 표시 불가라 목록에서 제외한다 — 보유 레코드 자체는 유지
    // (뽑기 중복 환급 판정은 보유 레코드 기준이라 영향 없음)
    @Query("select uc from UserCharacter uc "
            + "join fetch uc.character c "
            + "where uc.user.id = :userId and uc.deletedAt is null and c.active = true "
            + "order by c.sortOrder asc, uc.id asc")
    List<UserCharacter> findOwnedWithCharacter(@Param("userId") Long userId);
}
