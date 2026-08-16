package com.triples.rougether.adminapi.attendance.service;

import com.triples.rougether.adminapi.attendance.dto.AttendanceEventCreateRequest;
import com.triples.rougether.adminapi.attendance.dto.AttendanceEventCreateResponse;
import com.triples.rougether.adminapi.attendance.error.AttendanceEventAdminException;
import com.triples.rougether.domain.attendance.entity.AttendanceEvent;
import com.triples.rougether.domain.attendance.repository.AttendanceEventRepository;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.repository.ItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.SERIALIZABLE)
public class AttendanceEventAdminService {

    private static final String POSITIONED = "positioned";

    private final AttendanceEventRepository attendanceEventRepository;
    private final ItemRepository itemRepository;

    public AttendanceEventCreateResponse create(AttendanceEventCreateRequest request) {
        if (request.endsOn().isBefore(request.startsOn().plusDays(request.targetDays() - 1L))) {
            throw new AttendanceEventAdminException(
                    "ATTENDANCE_EVENT_PERIOD_TOO_SHORT",
                    "이벤트 기간은 목표 연속 출석일 이상이어야 합니다.", 400);
        }
        if (request.bonusDay() > request.targetDays()) {
            throw new AttendanceEventAdminException(
                    "ATTENDANCE_EVENT_BONUS_DAY_INVALID",
                    "보너스 일차는 목표 연속 출석일 이하여야 합니다.", 400);
        }
        if (attendanceEventRepository.existsByCode(request.code())) {
            throw new AttendanceEventAdminException(
                    "ATTENDANCE_EVENT_CODE_DUPLICATED", "이미 사용 중인 출석 이벤트 코드입니다.", 409);
        }
        if (attendanceEventRepository.countActiveOverlapping(request.startsOn(), request.endsOn()) > 0) {
            throw new AttendanceEventAdminException(
                    "ATTENDANCE_EVENT_PERIOD_OVERLAPPED", "진행 기간이 겹치는 활성 출석 이벤트가 있습니다.", 409);
        }

        Item rewardItem = itemRepository.findById(request.rewardItemId())
                .orElseThrow(() -> new AttendanceEventAdminException(
                        "ATTENDANCE_REWARD_ITEM_NOT_FOUND", "보상 아이템을 찾을 수 없습니다.", 404));
        if (!rewardItem.isActive() || !POSITIONED.equals(rewardItem.getPlacementType())) {
            throw new AttendanceEventAdminException(
                    "ATTENDANCE_REWARD_ITEM_INVALID", "활성 상태인 배치형 가구만 출석 보상으로 지정할 수 있습니다.", 400);
        }

        AttendanceEvent event = attendanceEventRepository.save(AttendanceEvent.create(
                request.code(), request.title(), request.startsOn(), request.endsOn(),
                request.targetDays(), request.dailyCoinAmount(), request.bonusDay(),
                request.bonusCoinAmount(), rewardItem));
        return AttendanceEventCreateResponse.of(event);
    }
}
