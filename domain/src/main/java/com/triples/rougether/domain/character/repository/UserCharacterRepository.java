package com.triples.rougether.domain.character.repository;

import com.triples.rougether.domain.character.entity.UserCharacter;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserCharacterRepository extends JpaRepository<UserCharacter, Long> {

    List<UserCharacter> findByUserId(Long userId);

    // 착용(대표) 캐릭터. 단일 착용(is_selected 는 동시에 1개만 true).
    Optional<UserCharacter> findByUserIdAndSelectedIsTrueAndDeletedAtIsNull(Long userId);
}
