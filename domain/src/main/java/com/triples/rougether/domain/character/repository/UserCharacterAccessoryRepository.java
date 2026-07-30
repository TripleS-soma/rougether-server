package com.triples.rougether.domain.character.repository;

import com.triples.rougether.domain.character.entity.UserCharacterAccessory;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserCharacterAccessoryRepository extends JpaRepository<UserCharacterAccessory, Long> {

    List<UserCharacterAccessory> findByUserCharacterId(Long userCharacterId);

    Optional<UserCharacterAccessory> findByUserCharacterIdAndCharacterSlotType(
            Long userCharacterId, String characterSlotType);

    @Query("""
            select accessory from UserCharacterAccessory accessory
            join fetch accessory.userItem userItem
            join fetch userItem.item item
            join item.theme theme
            where accessory.userCharacter.id = :userCharacterId
              and userItem.deletedAt is null
              and item.active = true
              and theme.active = true
            order by accessory.characterSlotType asc, userItem.id asc
            """)
    List<UserCharacterAccessory> findActiveByUserCharacterId(
            @Param("userCharacterId") Long userCharacterId);

    @Query("""
            select accessory from UserCharacterAccessory accessory
            join fetch accessory.userItem userItem
            join fetch userItem.item item
            join item.theme theme
            where accessory.userCharacter.id in :userCharacterIds
              and userItem.deletedAt is null
              and item.active = true
              and theme.active = true
            order by accessory.userCharacter.id asc, accessory.characterSlotType asc, userItem.id asc
            """)
    List<UserCharacterAccessory> findActiveByUserCharacterIdIn(
            @Param("userCharacterIds") Collection<Long> userCharacterIds);
}
