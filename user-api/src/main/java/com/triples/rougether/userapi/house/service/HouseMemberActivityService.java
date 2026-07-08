package com.triples.rougether.userapi.house.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.routine.entity.PrivacyScope;
import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import com.triples.rougether.domain.routine.entity.RoutineStatus;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.userapi.house.dto.HouseMemberRoutineCompletionListResponse;
import com.triples.rougether.userapi.house.dto.HouseMemberRoutineCompletionListResponse.CompletionSummary;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import com.triples.rougether.userapi.room.dto.RoomResponse;
import com.triples.rougether.userapi.room.service.RoomQueryService;
import com.triples.rougether.userapi.routine.dto.RoutineListResponse;
import com.triples.rougether.userapi.routine.dto.RoutineResponse;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 같은 집 멤버의 방·루틴·완료 내역 열람. owner 전용이 아니라 ACTIVE 구성원 누구나(본인 포함).
// 루틴·완료 내역은 카테고리 공개 범위(visibility)가 HOUSE/PUBLIC 인 것만 노출한다.
// FRIENDS(친한친구)는 집과 별개 개념이라 제외, 미분류(category null) 루틴은 비공개 취급 — spec 확정 전 보수적 기본값.
@Service
public class HouseMemberActivityService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int DEFAULT_PERIOD_DAYS = 14;
    private static final int MAX_PERIOD_DAYS = 92;
    // 집 멤버에게 열리는 공개 범위
    private static final List<PrivacyScope> HOUSE_VISIBLE_SCOPES =
            List.of(PrivacyScope.HOUSE, PrivacyScope.PUBLIC);

    private final HouseRepository houseRepository;
    private final HouseMemberRepository houseMemberRepository;
    private final RoomQueryService roomQueryService;
    private final RoutineRepository routineRepository;
    private final RoutineLogRepository routineLogRepository;

    public HouseMemberActivityService(HouseRepository houseRepository,
                                      HouseMemberRepository houseMemberRepository,
                                      RoomQueryService roomQueryService,
                                      RoutineRepository routineRepository,
                                      RoutineLogRepository routineLogRepository) {
        this.houseRepository = houseRepository;
        this.houseMemberRepository = houseMemberRepository;
        this.roomQueryService = roomQueryService;
        this.routineRepository = routineRepository;
        this.routineLogRepository = routineLogRepository;
    }

    // 멤버 방 조회 - 내 방(GET /rooms/me)과 같은 응답 형태. 방 미생성이면 ROOM_NOT_FOUND(404).
    @Transactional(readOnly = true)
    public RoomResponse getMemberRoom(Long userId, Long houseId, Long memberUserId) {
        requireSameHouseMembers(userId, memberUserId, houseId);
        return roomQueryService.getRoomOf(memberUserId);
    }

    // 멤버 활성 루틴 목록 - 공개 범위 통과분만.
    @Transactional(readOnly = true)
    public RoutineListResponse getMemberRoutines(Long userId, Long houseId, Long memberUserId) {
        requireSameHouseMembers(userId, memberUserId, houseId);
        return new RoutineListResponse(routineRepository
                .findVisibleByUserIdAndStatus(memberUserId, RoutineStatus.ACTIVE, HOUSE_VISIBLE_SCOPES)
                .stream()
                .map(RoutineResponse::from)
                .toList());
    }

    // 멤버 루틴 완료 내역 - 기간 필터(기본 최근 14일, 최대 92일), 공개 범위 통과분만.
    @Transactional(readOnly = true)
    public HouseMemberRoutineCompletionListResponse getMemberRoutineCompletions(
            Long userId, Long houseId, Long memberUserId, LocalDate from, LocalDate to) {
        requireSameHouseMembers(userId, memberUserId, houseId);

        LocalDate resolvedTo = to != null ? to : LocalDate.now(KST);
        LocalDate resolvedFrom = from != null ? from : resolvedTo.minusDays(DEFAULT_PERIOD_DAYS - 1);
        if (resolvedFrom.isAfter(resolvedTo)
                || ChronoUnit.DAYS.between(resolvedFrom, resolvedTo) >= MAX_PERIOD_DAYS) {
            throw new BusinessException(HouseErrorCode.HOUSE_ACTIVITY_PERIOD_INVALID);
        }

        List<CompletionSummary> items = routineLogRepository
                .findVisibleCompletedInPeriod(memberUserId, RoutineLogStatus.COMPLETED,
                        resolvedFrom, resolvedTo, HOUSE_VISIBLE_SCOPES)
                .stream()
                .map(CompletionSummary::of)
                .toList();
        return new HouseMemberRoutineCompletionListResponse(resolvedFrom, resolvedTo, items);
    }

    // 집 존재(삭제 제외) + 요청자·조회 대상 모두 그 집 ACTIVE 구성원인지 확인 (방명록과 동일 guard 정책)
    private void requireSameHouseMembers(Long userId, Long memberUserId, Long houseId) {
        houseRepository.findById(houseId)
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new BusinessException(HouseErrorCode.HOUSE_NOT_FOUND));
        houseMemberRepository.findByHouseIdAndUserId(houseId, userId)
                .filter(HouseMember::isActive)
                .orElseThrow(() -> new BusinessException(HouseErrorCode.HOUSE_NOT_MEMBER));
        houseMemberRepository.findByHouseIdAndUserId(houseId, memberUserId)
                .filter(HouseMember::isActive)
                .orElseThrow(() -> new BusinessException(HouseErrorCode.HOUSE_MEMBER_NOT_FOUND));
    }
}
