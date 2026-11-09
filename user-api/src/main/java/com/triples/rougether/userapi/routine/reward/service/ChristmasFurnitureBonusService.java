package com.triples.rougether.userapi.routine.reward.service;

import com.triples.rougether.domain.room.entity.RoomSlotType;
import com.triples.rougether.domain.room.repository.RoomFurnitureBonusRepository;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 완료 요청이 한 번 확정한 KST 오늘로 판정함. 출석·거래·환불 등 다른 지급 경로에서는 호출하지 않음.
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class ChristmasFurnitureBonusService {

    private static final LocalDate STARTS_ON = LocalDate.of(2026, 11, 9);
    private static final LocalDate ENDS_ON = LocalDate.of(2026, 12, 25);
    private static final String THEME_CODE = "snowy_christmas_202611";
    private static final List<String> POSITIONED_SLOTS = Arrays.stream(RoomSlotType.values())
            .map(RoomSlotType::code).filter(RoomSlotType::isPositionedCode).toList();

    private final RoomFurnitureBonusRepository roomFurnitureBonusRepository;
    private final boolean enabled;

    public ChristmasFurnitureBonusService(RoomFurnitureBonusRepository roomFurnitureBonusRepository,
                                         @Value("${christmas.coin-bonus.enabled:false}") boolean enabled,
                                         @Value("${christmas.event.enabled:false}") boolean eventEnabled) {
        this.roomFurnitureBonusRepository = roomFurnitureBonusRepository;
        this.enabled = enabled && eventEnabled;
    }

    // 가구 종류당 원금의 10%. 원금이 부분 지급이면 그 원금 기준으로 계산하고 마지막에 정수 버림함.
    public int bonusCoins(Long userId, LocalDate today, int baseAmount) {
        if (!enabled || baseAmount <= 0 || today.isBefore(STARTS_ON) || today.isAfter(ENDS_ON)) {
            return 0;
        }
        long kinds =
                roomFurnitureBonusRepository.countFreeLayoutFurnitureKinds(userId, THEME_CODE)
                        + roomFurnitureBonusRepository.countSlotLayoutFurnitureKinds(userId, THEME_CODE, POSITIONED_SLOTS);
        return Math.toIntExact((long) baseAmount * kinds / 10);
    }
}
