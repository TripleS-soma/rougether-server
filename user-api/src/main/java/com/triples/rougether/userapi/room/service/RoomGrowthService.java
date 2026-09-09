package com.triples.rougether.userapi.room.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.character.entity.Character;
import com.triples.rougether.domain.character.entity.UserCharacter;
import com.triples.rougether.domain.character.repository.CharacterRepository;
import com.triples.rougether.domain.character.repository.UserCharacterRepository;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.room.entity.PersonalRoom;
import com.triples.rougether.domain.room.repository.PersonalRoomRepository;
import com.triples.rougether.userapi.member.error.MemberErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 완료/취소 트랜잭션에 참여함. 공통 잠금 순서는 user → coin wallet → personal room임.
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class RoomGrowthService {

    private static final int MORU_REQUIRED_LEVEL = 5;

    private final PersonalRoomRepository personalRoomRepository;
    private final UserRepository userRepository;
    private final CharacterRepository characterRepository;
    private final UserCharacterRepository userCharacterRepository;

    // 일반 SELECT보다 먼저 호출해야 REPEATABLE_READ 스냅샷도 잠금 뒤에 생성됨.
    // 온보딩·어드민 지급·뽑기와 같은 user 락으로 캐릭터 보유 판정을 직렬화함.
    public void lockUser(Long userId) {
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.USER_NOT_FOUND));
    }

    // 호출자가 user·coin wallet 락을 먼저 보유해야 함.
    public void award(Long userId, int amount) {
        if (amount == 0) {
            return;
        }
        requirePositive(amount);
        personalRoomRepository.ensureExists(userId);
        PersonalRoom room = lockedRoom(userId);
        room.changeGrowthPoints(amount);
        grantEligibleReward(room);
    }

    public void revoke(Long userId, int amount) {
        if (amount == 0) {
            return;
        }
        requirePositive(amount);
        lockedRoom(userId).changeGrowthPoints(-amount);
    }

    // 내 방/보유 캐릭터 조회의 첫 작업으로 호출함. 기존 달성자·지연 활성화 보상을 보정함.
    public void grantPendingReward(Long userId) {
        lockUser(userId);
        personalRoomRepository.findWithLockById(userId).ifPresent(this::grantEligibleReward);
    }

    private void grantEligibleReward(PersonalRoom room) {
        if (Math.max(room.getHighestGrowthLevel(), room.getGrowthLevel()) < MORU_REQUIRED_LEVEL) {
            return;
        }
        // 에셋 검증을 통과해 활성화된 카탈로그만 지급함. 미등록/비활성 상태여도 완료는 성공함.
        characterRepository.findByCode(Character.MORU_CODE).filter(Character::isActive).ifPresent(character -> {
            if (!userCharacterRepository.existsByUserIdAndCharacterId(room.getUserId(), character.getId())) {
                userCharacterRepository.save(UserCharacter.create(room.getUser(), character));
            }
        });
    }

    private PersonalRoom lockedRoom(Long userId) {
        return personalRoomRepository.findWithLockById(userId)
                .orElseThrow(() -> new IllegalStateException("성장 포인트를 반영할 개인 방이 없음"));
    }

    private void requirePositive(int amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("성장 지급/회수량은 음수가 될 수 없음");
        }
    }
}
