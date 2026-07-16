package com.triples.rougether.userapi.character.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.character.entity.UserCharacter;
import com.triples.rougether.domain.character.repository.UserCharacterRepository;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.member.error.MemberErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 착용(대표) 캐릭터 교체. 보유한 캐릭터로만 교체할 수 있고, 무료 지급은 온보딩 경로가 소유한다.
@Service
public class MyCharacterCommandService {

    private final UserCharacterRepository userCharacterRepository;
    private final UserRepository userRepository;

    public MyCharacterCommandService(UserCharacterRepository userCharacterRepository,
                                     UserRepository userRepository) {
        this.userCharacterRepository = userCharacterRepository;
        this.userRepository = userRepository;
    }

    // 동시 교체가 착용 2개를 만들지 않게 user 행 잠금으로 직렬화 (온보딩 선택과 동일 락 대상이라 상호 배제됨)
    @Transactional
    public Long select(Long userId, Long characterId) {
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.USER_NOT_FOUND));

        UserCharacter target = userCharacterRepository
                .findByUserIdAndCharacterIdAndDeletedAtIsNull(userId, characterId)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.CHARACTER_NOT_OWNED));

        // 보유 중이어도 비활성(운영 회수) 캐릭터는 착용 불가 — 에셋이 내려가 화면이 깨진다 (온보딩 선택과 동일 정책)
        if (!target.getCharacter().isActive()) {
            throw new BusinessException(MemberErrorCode.CHARACTER_NOT_FOUND);
        }

        if (!target.isSelected()) {
            userCharacterRepository.findByUserIdAndSelectedTrueAndDeletedAtIsNull(userId)
                    .ifPresent(UserCharacter::unselect);
            target.select();
        }
        return characterId;
    }
}
