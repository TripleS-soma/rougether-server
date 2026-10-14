package com.triples.rougether.userapi.character.service;

import com.triples.rougether.domain.room.entity.PersonalRoom;
import com.triples.rougether.domain.room.repository.PersonalRoomRepository;
import org.springframework.stereotype.Component;

// 캐릭터 뽑기·착용 교체 해금 정책(제품 결정 2026-10-14). 레벨은 달성 기준이라 완료 취소로 내려가도 해금을 유지함.
// 방이 아직 없으면 레벨 0으로 봄. 뽑기·교체 guard와 내 방 응답이 이 한 곳의 기준을 공유함.
@Component
public class CharacterUnlockPolicy {

    public static final int CHARACTER_UNLOCK_LEVEL = 10;

    private final PersonalRoomRepository personalRoomRepository;

    public CharacterUnlockPolicy(PersonalRoomRepository personalRoomRepository) {
        this.personalRoomRepository = personalRoomRepository;
    }

    // 잠금 없는 일반 조회임. 호출자가 잡은 user·wallet 락 순서에 방 락을 끼워 넣지 않음.
    public boolean isUnlocked(Long userId) {
        return personalRoomRepository.findById(userId).map(CharacterUnlockPolicy::isUnlocked).orElse(false);
    }

    public static boolean isUnlocked(PersonalRoom room) {
        return achievedLevel(room) >= CHARACTER_UNLOCK_LEVEL;
    }

    private static int achievedLevel(PersonalRoom room) {
        return Math.max(room.getHighestGrowthLevel(), room.getGrowthLevel());
    }
}
