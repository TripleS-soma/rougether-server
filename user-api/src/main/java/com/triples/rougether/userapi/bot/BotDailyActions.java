package com.triples.rougether.userapi.bot;

import com.triples.rougether.domain.house.entity.HouseMission;
import com.triples.rougether.domain.house.repository.HouseMissionDailyContributionRepository;
import com.triples.rougether.domain.house.repository.HouseMissionRepository;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import com.triples.rougether.domain.routine.entity.RoutineStatus;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.userapi.bot.BotTickContext.HouseSnapshot;
import com.triples.rougether.userapi.house.service.HouseMissionService;
import com.triples.rougether.userapi.routine.dto.RoutineLogCreateRequest;
import com.triples.rougether.userapi.routine.service.RoutineLogService;
import java.util.List;
import java.util.OptionalInt;
import org.springframework.stereotype.Component;

// 봇의 "하루 성과" 행동(#310): 루틴 완료·단체 미션 기여. 쉬는 날(15%)에는 둘 다 건너뛴다.
// 쓰기는 전부 기존 서비스 경유 — 완료 보상 10코인·일일 상한 50·스트릭, 미션 기여 하루 1회·달성률·성장 포인트가
// 사람과 동일하게 적용된다(결정 A). claim 은 봇이 하지 않는다.
@Component
public class BotDailyActions {

    private final RoutineRepository routineRepository;
    private final RoutineLogRepository routineLogRepository;
    private final HouseMissionRepository houseMissionRepository;
    private final HouseMissionDailyContributionRepository dailyContributionRepository;
    private final RoutineLogService routineLogService;
    private final HouseMissionService houseMissionService;

    public BotDailyActions(RoutineRepository routineRepository,
                           RoutineLogRepository routineLogRepository,
                           HouseMissionRepository houseMissionRepository,
                           HouseMissionDailyContributionRepository dailyContributionRepository,
                           RoutineLogService routineLogService,
                           HouseMissionService houseMissionService) {
        this.routineRepository = routineRepository;
        this.routineLogRepository = routineLogRepository;
        this.houseMissionRepository = houseMissionRepository;
        this.dailyContributionRepository = dailyContributionRepository;
        this.routineLogService = routineLogService;
        this.houseMissionService = houseMissionService;
    }

    // 목표 틱이 지난 미완료 루틴 — 각 항목은 호출자가 개별 트랜잭션·실패 격리로 실행한다.
    public List<Routine> routinesDue(BotTickContext context) {
        if (context.restDay()) {
            return List.of();
        }
        return routineRepository
                .findByUserIdAndStatusAndDeletedAtIsNullOrderByScheduledTimeAscOriginRoutineIdAsc(
                        context.botId(), RoutineStatus.ACTIVE)
                .stream()
                .filter(routine -> BotDecision.isDue(
                        BotDecision.routineCompletionTick(context.botId(), context.date(), routine.getId(), context.profile()),
                        context.tick()))
                .filter(routine -> routineLogRepository.findByRoutineIdAndRoutineDateAndStatus(
                        routine.getId(), context.date(), RoutineLogStatus.COMPLETED).isEmpty())
                .toList();
    }

    public void completeRoutine(BotTickContext context, Routine routine) {
        routineLogService.complete(context.botId(), routine.getId(), new RoutineLogCreateRequest(context.date()));
    }

    // 목표 틱이 지났고 오늘 아직 기여하지 않은 진행 중 미션.
    public List<HouseMission> missionsDue(BotTickContext context, HouseSnapshot house) {
        if (context.restDay()) {
            return List.of();
        }
        return houseMissionRepository
                .findByHouseIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(house.houseId())
                .stream()
                .filter(mission -> mission.isActive() && mission.isWithinPeriod(context.now()))
                .filter(mission -> {
                    OptionalInt target = BotDecision.missionContributionTick(
                            context.botId(), context.date(), mission.getId(), context.profile());
                    return BotDecision.isDue(target, context.tick());
                })
                .filter(mission -> !dailyContributionRepository.existsByMissionIdAndMemberIdAndContributionDate(
                        mission.getId(), house.botMembership().getId(), context.date()))
                .toList();
    }

    public void contribute(BotTickContext context, HouseSnapshot house, HouseMission mission) {
        houseMissionService.contribute(context.botId(), house.houseId(), mission.getId());
    }
}
