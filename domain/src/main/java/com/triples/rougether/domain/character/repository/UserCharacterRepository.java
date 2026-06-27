package com.triples.rougether.domain.character.repository;

import com.triples.rougether.domain.character.entity.UserCharacter;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserCharacterRepository extends JpaRepository<UserCharacter, Long> {

    List<UserCharacter> findByUserId(Long userId);
}
