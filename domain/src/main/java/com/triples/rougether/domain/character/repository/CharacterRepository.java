package com.triples.rougether.domain.character.repository;

import com.triples.rougether.domain.character.entity.Character;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CharacterRepository extends JpaRepository<Character, Long> {

    Optional<Character> findByCode(String code);

    List<Character> findByActiveTrueOrderBySortOrderAsc();

    // admin 카탈로그 화면용: 비활성 포함 전체.
    List<Character> findAllByOrderBySortOrderAscIdAsc();

    boolean existsByBaseAssetKey(String baseAssetKey);
}
