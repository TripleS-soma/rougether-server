package com.triples.rougether.domain.character.repository;

import com.triples.rougether.domain.character.entity.CharacterAccessoryRenderProfile;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CharacterAccessoryRenderProfileRepository
        extends JpaRepository<CharacterAccessoryRenderProfile, Long> {

    boolean existsByItemIdAndCharacterIdAndRenderState(
            Long itemId, Long characterId, String renderState);

    Optional<CharacterAccessoryRenderProfile> findByItemIdAndCharacterIdAndRenderState(
            Long itemId, Long characterId, String renderState);

    @Query("""
            select profile from CharacterAccessoryRenderProfile profile
            join fetch profile.item item
            join fetch profile.character character
            where item.id in :itemIds
              and character.id in :characterIds
            order by character.id asc, item.id asc, profile.renderState asc
            """)
    List<CharacterAccessoryRenderProfile> findByItemIdInAndCharacterIdIn(
            @Param("itemIds") Collection<Long> itemIds,
            @Param("characterIds") Collection<Long> characterIds);

    @Query("""
            select profile from CharacterAccessoryRenderProfile profile
            join fetch profile.item item
            join fetch item.theme
            join fetch profile.character character
            order by item.id asc, character.sortOrder asc, profile.renderState asc
            """)
    List<CharacterAccessoryRenderProfile> findAllWithItemAndCharacter();
}
