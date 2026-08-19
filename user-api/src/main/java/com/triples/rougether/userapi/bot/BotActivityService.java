package com.triples.rougether.userapi.bot;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberStatus;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.bot.BotTickContext.HouseSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// 동거 봇 활동 틱 처리(#310). 스케줄러(BotActivityScheduler)가 10분마다 runTick() 을 부른다.
// 이 클래스는 트랜잭션을 열지 않는다 — 봇 1명·행동 1건이 기존 서비스의 트랜잭션 1개로 커밋되고,
// 예외는 warn 로그 후 다음 행동/봇으로 넘어간다(전체 틱 중단 없음). 처리 순서는 봇 id 오름차순, 집 id 오름차순.
// 처리 대상: 카탈로그에 있는 봇 중 활동 창 안이고 "사람 ACTIVE 구성원이 1명 이상인 집"에 속한 봇만.
@Slf4j
@Service
public class BotActivityService {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static final Map<String, BotProfile> PROFILES_BY_KEY = BotProfileCatalog.PROFILES.stream()
            .collect(Collectors.toMap(BotProfile::botKey, Function.identity()));

    private final UserRepository userRepository;
    private final HouseMemberRepository houseMemberRepository;
    private final BotDailyActions dailyActions;
    private final BotSocialActions socialActions;
    private final Clock clock;

    public BotActivityService(UserRepository userRepository,
                              HouseMemberRepository houseMemberRepository,
                              BotDailyActions dailyActions,
                              BotSocialActions socialActions,
                              Clock clock) {
        this.userRepository = userRepository;
        this.houseMemberRepository = houseMemberRepository;
        this.dailyActions = dailyActions;
        this.socialActions = socialActions;
        this.clock = clock;
    }

    public BotTickReport runTick() {
        return runTick(ZonedDateTime.now(clock));
    }

    // 테스트·수동 실행용 진입점: 틱 시각을 명시한다(KST 로 환산해 날짜·틱 인덱스를 계산).
    public BotTickReport runTick(ZonedDateTime at) {
        ZonedDateTime kst = at.withZoneSameInstant(KST);
        LocalDate date = kst.toLocalDate();
        LocalTime time = kst.toLocalTime();
        int tick = BotDecision.tickIndex(time);
        Instant now = kst.toInstant();
        Instant todayStart = date.atStartOfDay(KST).toInstant();

        BotTickReport report = new BotTickReport();
        List<User> bots = userRepository.findAllByBotTrueAndDeletedAtIsNull().stream()
                .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
                .toList();
        for (User bot : bots) {
            report.botSeen();
            BotProfile profile = PROFILES_BY_KEY.get(bot.getBotKey());
            if (profile == null) {
                log.warn("동거 봇 활동 - 카탈로그에 없는 봇은 건너뜀 botKey={}", bot.getBotKey());
                continue;
            }
            if (!profile.activity().isActiveAt(time)) {
                continue;
            }
            // 봇 단위 격리: 판정(조회) 단계에서 던져도 다음 봇은 계속 처리한다.
            try {
                List<HouseSnapshot> houses = housesWithHumans(bot);
                if (houses.isEmpty()) {
                    continue;
                }
                BotTickContext context = new BotTickContext(bot, profile.activity(), date, time, tick, now, todayStart,
                        BotDecision.isRestDay(bot.getId(), date), houses);
                report.botActed();
                act(context, report);
            } catch (RuntimeException error) {
                report.failed();
                log.warn("동거 봇 처리 실패 - botKey={}, tick={}: {}", bot.getBotKey(), tick, error.toString());
            }
        }
        return report;
    }

    // 봇이 ACTIVE 로 속한 집 중 삭제되지 않았고 사람 ACTIVE 구성원이 1명 이상인 집. 사람/봇 멤버 목록을 함께 스냅샷.
    private List<HouseSnapshot> housesWithHumans(User bot) {
        List<HouseSnapshot> snapshots = new ArrayList<>();
        List<HouseMember> memberships = houseMemberRepository
                .findByUserIdAndStatusWithHouse(bot.getId(), HouseMemberStatus.ACTIVE).stream()
                .sorted((a, b) -> Long.compare(a.getHouse().getId(), b.getHouse().getId()))
                .toList();
        for (HouseMember membership : memberships) {
            House house = membership.getHouse();
            if (house.isDeleted()) {
                continue;
            }
            List<HouseMember> active = houseMemberRepository
                    .findByHouseIdAndStatusWithUser(house.getId(), HouseMemberStatus.ACTIVE);
            List<HouseMember> humans = active.stream().filter(m -> !m.getUser().isBot()).toList();
            if (humans.isEmpty()) {
                continue;
            }
            List<HouseMember> botMembers = active.stream().filter(m -> m.getUser().isBot()).toList();
            snapshots.add(new HouseSnapshot(house, membership, humans, botMembers));
        }
        return snapshots;
    }

    // 판정(*Due, 읽기)과 실행(run, 쓰기)을 행동 종류·집 단위로 각각 격리한다 — 한 판정이 던져도 같은 봇의 다른 행동·다음 집은 계속.
    private void act(BotTickContext context, BotTickReport report) {
        for (var routine : plan(report, "routine-complete", context, () -> dailyActions.routinesDue(context))) {
            run(report, "routine-complete", context, routine.getId(),
                    () -> dailyActions.completeRoutine(context, routine), report::routineCompleted);
        }
        for (HouseSnapshot house : context.houses()) {
            for (var mission : plan(report, "mission-contribute", context, () -> dailyActions.missionsDue(context, house))) {
                run(report, "mission-contribute", context, mission.getId(),
                        () -> dailyActions.contribute(context, house, mission), report::missionContributed);
            }
            for (var cheer : plan(report, "cheer", context, () -> socialActions.cheersDue(context, house))) {
                run(report, "cheer", context, cheer.target().getId(),
                        () -> socialActions.cheer(context, house, cheer), report::cheerSent);
            }
            plan(report, "guestbook", context, () -> socialActions.guestbookDue(context, house).stream().toList())
                    .forEach(plan -> run(report, "guestbook", context, plan.target().getId(),
                            () -> socialActions.writeGuestbook(context, house, plan), report::guestbookWritten));
            for (HouseMember room : plan(report, "cobweb-clean", context, () -> socialActions.cobwebRoomsDue(context, house))) {
                run(report, "cobweb-clean", context, room.getId(),
                        () -> socialActions.cleanCobweb(context, house, room), report::cobwebCleaned);
            }
        }
        plan(report, "layout-rotate", context, () -> socialActions.layoutRotationDue(context).stream().toList())
                .forEach(plan -> run(report, "layout-rotate", context, plan.preset().ordinal(),
                        () -> socialActions.rotateLayout(context, plan), report::layoutRotated));
    }

    // 판정 단계 격리: 조회가 던지면 warn 후 "할 일 없음"으로 취급한다(다음 틱에 다시 판정한다).
    private <T> List<T> plan(BotTickReport report, String action, BotTickContext context, Supplier<List<T>> planner) {
        try {
            return planner.get();
        } catch (RuntimeException error) {
            report.failed();
            log.warn("동거 봇 판정 실패 - action={}, botKey={}, tick={}: {}",
                    action, context.bot().getBotKey(), context.tick(), error.toString());
            return List.of();
        }
    }

    // 행동 1건 = 트랜잭션 1개(기존 서비스 @Transactional/TransactionTemplate). 실패는 격리하고 다음으로 진행한다.
    // 기존 서비스의 비즈니스 거절(이미 완료·거미줄 없음·revision 충돌 등)은 정상 경합 흡수라 info 로 남기고 failures 와 분리한다(틱 종료 로그의 businessSkips 로 집계).
    private void run(BotTickReport report, String action, BotTickContext context, long targetId,
                     Runnable body, Runnable onSuccess) {
        try {
            body.run();
            onSuccess.run();
        } catch (BusinessException rejected) {
            report.businessSkipped();
            // info 로 남긴다 — 정상 경합이 대부분이지만 WALLET_NOT_FOUND 같은 시드 결함도 여기로 오므로 완전히 숨기지 않는다.
            log.info("동거 봇 행동 거절 - action={}, botKey={}, target={}, tick={}, code={}",
                    action, context.bot().getBotKey(), targetId, context.tick(), rejected.getErrorCode());
        } catch (RuntimeException error) {
            report.failed();
            log.warn("동거 봇 행동 실패 - action={}, botKey={}, target={}, tick={}: {}",
                    action, context.bot().getBotKey(), targetId, context.tick(), error.toString());
        }
    }
}
