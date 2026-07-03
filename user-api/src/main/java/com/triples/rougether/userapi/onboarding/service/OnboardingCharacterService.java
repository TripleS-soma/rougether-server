package com.triples.rougether.userapi.onboarding.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.character.entity.Character;
import com.triples.rougether.domain.character.entity.UserCharacter;
import com.triples.rougether.domain.character.repository.CharacterRepository;
import com.triples.rougether.domain.character.repository.UserCharacterRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.member.error.MemberErrorCode;
import com.triples.rougether.userapi.onboarding.dto.OnboardingCharacterResponse;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OnboardingCharacterService {

    private final CharacterRepository characterRepository;
    private final UserCharacterRepository userCharacterRepository;
    private final UserRepository userRepository;

    public OnboardingCharacterService(CharacterRepository characterRepository,
                                      UserCharacterRepository userCharacterRepository,
                                      UserRepository userRepository) {
        this.characterRepository = characterRepository;
        this.userCharacterRepository = userCharacterRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public OnboardingCharacterResponse selectCharacter(Long userId, Long characterId) {
        Character character = characterRepository.findById(characterId)
                .filter(Character::isActive)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.CHARACTER_NOT_FOUND));

        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.USER_NOT_FOUND));

        Optional<UserCharacter> owned =
                userCharacterRepository.findByUserIdAndCharacterIdAndDeletedAtIsNull(userId, characterId);
        if (owned.isPresent()) {
            UserCharacter target = owned.get();
            if (!target.isSelected()) {
                userCharacterRepository.findByUserIdAndSelectedTrueAndDeletedAtIsNull(userId)
                        .ifPresent(UserCharacter::unselect);
                target.select();
                userCharacterRepository.save(target);
            }
            return new OnboardingCharacterResponse(characterId);
        }

        if (!userCharacterRepository.findByUserIdAndDeletedAtIsNull(userId).isEmpty()) {
            throw new BusinessException(MemberErrorCode.CHARACTER_NOT_OWNED);
        }

        userCharacterRepository.save(UserCharacter.of(user, character, Instant.now(), true));
        return new OnboardingCharacterResponse(characterId);
    }
}
