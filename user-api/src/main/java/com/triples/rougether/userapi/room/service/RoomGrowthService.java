package com.triples.rougether.userapi.room.service;

import com.triples.rougether.common.error.BusinessException;
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

    private final PersonalRoomRepository personalRoomRepository;
    private final UserRepository userRepository;

    // 일반 SELECT보다 먼저 호출해야 REPEATABLE_READ 스냅샷도 잠금 뒤에 생성됨.
    // 완료/취소의 공통 잠금 순서(user → coin wallet → personal room)의 첫 단계임.
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
        // 포인트·레벨만 반영함. 레벨 달성 캐릭터 지급(모루)은 2026-10-14 제거함.
        room.changeGrowthPoints(amount);
    }

    public void revoke(Long userId, int amount) {
        if (amount == 0) {
            return;
        }
        requirePositive(amount);
        lockedRoom(userId).changeGrowthPoints(-amount);
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
