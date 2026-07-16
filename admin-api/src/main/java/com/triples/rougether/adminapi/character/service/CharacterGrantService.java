package com.triples.rougether.adminapi.character.service;

import com.triples.rougether.adminapi.character.dto.CharacterGrantResponse;
import com.triples.rougether.domain.character.entity.Character;
import com.triples.rougether.domain.character.entity.UserCharacter;
import com.triples.rougether.domain.character.repository.CharacterRepository;
import com.triples.rougether.domain.character.repository.UserCharacterRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import java.util.NoSuchElementException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 개발/QA용 캐릭터 지급. 이미 보유면 멱등(no-op), 첫 캐릭터면 착용까지 해서 방이 비지 않게 한다.
@Service
public class CharacterGrantService {

    private final UserRepository userRepository;
    private final CharacterRepository characterRepository;
    private final UserCharacterRepository userCharacterRepository;

    public CharacterGrantService(UserRepository userRepository,
                                 CharacterRepository characterRepository,
                                 UserCharacterRepository userCharacterRepository) {
        this.userRepository = userRepository;
        this.characterRepository = characterRepository;
        this.userCharacterRepository = userCharacterRepository;
    }

    @Transactional
    public CharacterGrantResponse grant(Long userId, String characterCode) {
        // 온보딩 선택·착용 교체와 같은 user 행 락 — 동시 지급/선택이 착용 2개를 만들지 않게 직렬화
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new NoSuchElementException("존재하지 않는 회원입니다: " + userId));
        Character character = characterRepository.findByCode(characterCode)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 캐릭터 코드: " + characterCode));
        if (!character.isActive()) {
            throw new IllegalArgumentException("회수(비활성)된 캐릭터는 지급할 수 없습니다: " + characterCode);
        }

        boolean alreadyOwned = userCharacterRepository
                .findByUserIdAndCharacterIdAndDeletedAtIsNull(userId, character.getId())
                .isPresent();
        if (alreadyOwned) {
            return new CharacterGrantResponse(userId, character.getId(), characterCode, true, false);
        }

        boolean first = userCharacterRepository.findByUserIdAndDeletedAtIsNull(userId).isEmpty();
        userCharacterRepository.save(first
                ? UserCharacter.createSelected(user, character)
                : UserCharacter.create(user, character));
        return new CharacterGrantResponse(userId, character.getId(), characterCode, false, first);
    }
}
