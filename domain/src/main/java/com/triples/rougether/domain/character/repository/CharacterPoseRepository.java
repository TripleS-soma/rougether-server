package com.triples.rougether.domain.character.repository;

import com.triples.rougether.domain.character.entity.CharacterPose;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CharacterPoseRepository extends JpaRepository<CharacterPose, Long> {

    Optional<CharacterPose> findByCharacterIdAndCode(Long characterId, String code);

    List<CharacterPose> findByCharacterIdAndActiveTrueOrderBySortOrderAsc(Long characterId);

    boolean existsByAssetKey(String assetKey);

    @Query("""
            select pose from CharacterPose pose
            join fetch pose.character character
            order by character.sortOrder asc, pose.sortOrder asc, pose.id asc
            """)
    List<CharacterPose> findAllWithCharacter();
}
