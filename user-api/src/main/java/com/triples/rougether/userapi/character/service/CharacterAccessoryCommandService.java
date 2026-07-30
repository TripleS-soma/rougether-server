package com.triples.rougether.userapi.character.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.character.entity.UserCharacter;
import com.triples.rougether.domain.character.entity.UserCharacterAccessory;
import com.triples.rougether.domain.character.repository.UserCharacterAccessoryRepository;
import com.triples.rougether.domain.character.repository.UserCharacterRepository;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.UserItem;
import com.triples.rougether.domain.shop.repository.UserItemRepository;
import com.triples.rougether.userapi.character.dto.CharacterAccessoriesResponse;
import com.triples.rougether.userapi.character.error.CharacterAccessoryErrorCode;
import com.triples.rougether.userapi.member.error.MemberErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CharacterAccessoryCommandService {

    private static final String PLACEMENT_CHARACTER = "character";

    private final UserRepository userRepository;
    private final UserCharacterRepository userCharacterRepository;
    private final UserItemRepository userItemRepository;
    private final UserCharacterAccessoryRepository userCharacterAccessoryRepository;

    public CharacterAccessoryCommandService(
            UserRepository userRepository,
            UserCharacterRepository userCharacterRepository,
            UserItemRepository userItemRepository,
            UserCharacterAccessoryRepository userCharacterAccessoryRepository) {
        this.userRepository = userRepository;
        this.userCharacterRepository = userCharacterRepository;
        this.userItemRepository = userItemRepository;
        this.userCharacterAccessoryRepository = userCharacterAccessoryRepository;
    }

    @Transactional
    public CharacterAccessoriesResponse equip(Long userId, Long userCharacterId, Long userItemId) {
        lockUser(userId);
        UserCharacter userCharacter = ownedCharacter(userId, userCharacterId);
        UserItem userItem = userItemRepository.findOwnedWithItem(userId, userItemId)
                .orElseThrow(() -> new BusinessException(
                        CharacterAccessoryErrorCode.ACCESSORY_NOT_OWNED));
        Item item = userItem.getItem();
        validateAccessory(item);

        userCharacterAccessoryRepository
                .findByUserCharacterIdAndCharacterSlotType(
                        userCharacterId, item.getCharacterSlotType())
                .ifPresentOrElse(
                        accessory -> {
                            if (!accessory.getUserItem().getId().equals(userItemId)) {
                                accessory.replaceWith(userItem);
                            }
                        },
                        () -> userCharacterAccessoryRepository.save(
                                UserCharacterAccessory.equip(
                                        userCharacter, userItem, item.getCharacterSlotType())));

        return response(userCharacterId);
    }

    @Transactional
    public CharacterAccessoriesResponse unequip(
            Long userId, Long userCharacterId, String characterSlotType) {
        lockUser(userId);
        ownedCharacter(userId, userCharacterId);
        userCharacterAccessoryRepository
                .findByUserCharacterIdAndCharacterSlotType(userCharacterId, characterSlotType)
                .ifPresent(userCharacterAccessoryRepository::delete);
        return response(userCharacterId);
    }

    private void lockUser(Long userId) {
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.USER_NOT_FOUND));
    }

    private UserCharacter ownedCharacter(Long userId, Long userCharacterId) {
        return userCharacterRepository.findByIdAndUserIdAndDeletedAtIsNull(userCharacterId, userId)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.CHARACTER_NOT_OWNED));
    }

    private void validateAccessory(Item item) {
        if (!item.isActive()
                || !item.getTheme().isActive()
                || !PLACEMENT_CHARACTER.equals(item.getPlacementType())
                || item.getCharacterSlotType() == null
                || item.getCharacterSlotType().isBlank()) {
            throw new BusinessException(CharacterAccessoryErrorCode.ACCESSORY_INVALID);
        }
    }

    private CharacterAccessoriesResponse response(Long userCharacterId) {
        return CharacterAccessoriesResponse.of(
                userCharacterId,
                userCharacterAccessoryRepository.findActiveByUserCharacterId(userCharacterId));
    }
}
