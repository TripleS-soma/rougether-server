package com.triples.rougether.userapi.bot;

import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberCheer;
import com.triples.rougether.domain.house.repository.HouseMemberCheerRepository;
import com.triples.rougether.domain.room.entity.PersonalRoom;
import com.triples.rougether.domain.room.entity.RoomCobweb;
import com.triples.rougether.domain.room.repository.PersonalRoomRepository;
import com.triples.rougether.domain.room.repository.RoomCobwebRepository;
import com.triples.rougether.domain.room.repository.RoomGuestbookRepository;
import com.triples.rougether.domain.routine.entity.PrivacyScope;
import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import com.triples.rougether.domain.routine.entity.TodoStatus;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import com.triples.rougether.domain.shop.entity.UserItem;
import com.triples.rougether.domain.shop.repository.UserItemRepository;
import com.triples.rougether.domain.support.UserLatestInstant;
import com.triples.rougether.userapi.bot.BotTickContext.HouseSnapshot;
import com.triples.rougether.userapi.guestbook.dto.GuestbookCreateRequest;
import com.triples.rougether.userapi.guestbook.service.GuestbookService;
import com.triples.rougether.userapi.house.service.HouseCheerService;
import com.triples.rougether.userapi.room.dto.RoomLayoutUpdateRequest;
import com.triples.rougether.userapi.room.service.RoomCobwebService;
import com.triples.rougether.userapi.room.service.RoomCommandService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.BinaryOperator;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

// 봇의 "함께 사는 느낌" 행동(#310): 응원·방명록·거미줄 청소·방 레이아웃 순환.
// 판정은 여기서, 쓰기는 기존 서비스(응원 5회/일 한도·방명록 금칙어·거미줄 청소 보상 3코인·레이아웃 revision 충돌)가
// 각자 트랜잭션에서 다시 검증한다.
@Component
public class BotSocialActions {

    private static final String POSITIONED_PLACEMENT_TYPE = "positioned";
    private static final List<PrivacyScope> HOUSE_VISIBLE_SCOPES = List.of(PrivacyScope.HOUSE, PrivacyScope.PUBLIC);

    private final RoutineLogRepository routineLogRepository;
    private final TodoRepository todoRepository;
    private final HouseMemberCheerRepository houseMemberCheerRepository;
    private final RoomGuestbookRepository roomGuestbookRepository;
    private final RoomCobwebRepository roomCobwebRepository;
    private final PersonalRoomRepository personalRoomRepository;
    private final UserItemRepository userItemRepository;
    private final HouseCheerService houseCheerService;
    private final GuestbookService guestbookService;
    private final RoomCobwebService roomCobwebService;
    private final RoomCommandService roomCommandService;

    public BotSocialActions(RoutineLogRepository routineLogRepository,
                            TodoRepository todoRepository,
                            HouseMemberCheerRepository houseMemberCheerRepository,
                            RoomGuestbookRepository roomGuestbookRepository,
                            RoomCobwebRepository roomCobwebRepository,
                            PersonalRoomRepository personalRoomRepository,
                            UserItemRepository userItemRepository,
                            HouseCheerService houseCheerService,
                            GuestbookService guestbookService,
                            RoomCobwebService roomCobwebService,
                            RoomCommandService roomCommandService) {
        this.routineLogRepository = routineLogRepository;
        this.todoRepository = todoRepository;
        this.houseMemberCheerRepository = houseMemberCheerRepository;
        this.roomGuestbookRepository = roomGuestbookRepository;
        this.roomCobwebRepository = roomCobwebRepository;
        this.personalRoomRepository = personalRoomRepository;
        this.userItemRepository = userItemRepository;
        this.houseCheerService = houseCheerService;
        this.guestbookService = guestbookService;
        this.roomCobwebService = roomCobwebService;
        this.roomCommandService = roomCommandService;
    }

    // ---- 응원: 같은 집 사람이 오늘 완료(루틴·투두)한 뒤 30~90분 창에서, 사람당 하루 2회 미만이면 1회 ----

    public record CheerPlan(HouseMember target, String type) {
    }

    public List<CheerPlan> cheersDue(BotTickContext context, HouseSnapshot house) {
        List<HouseMember> humans = house.humans();
        if (humans.isEmpty()) {
            return List.of();
        }
        Map<Long, Instant> latestCompletion = latestCompletionByUser(
                humans.stream().map(m -> m.getUser().getId()).toList(), context.todayStart());
        if (latestCompletion.isEmpty()) {
            return List.of();
        }
        Map<Long, List<HouseMemberCheer>> sentToday = houseMemberCheerRepository
                .findBySender_IdAndCheerDate(context.botId(), context.date())
                .stream()
                .collect(Collectors.groupingBy(cheer -> cheer.getTarget().getId()));

        return humans.stream()
                .filter(human -> latestCompletion.containsKey(human.getUser().getId()))
                .filter(human -> isCheerDue(context, human.getUser().getId(),
                        latestCompletion.get(human.getUser().getId()), sentToday.getOrDefault(human.getUser().getId(), List.of())))
                .map(human -> new CheerPlan(human, BotDecision.cheerType(context.botId(), context.date(),
                        human.getUser().getId(), latestCompletion.get(human.getUser().getId()).getEpochSecond())))
                .toList();
    }

    private boolean isCheerDue(BotTickContext context, long targetUserId, Instant completedAt,
                               List<HouseMemberCheer> sentToTarget) {
        if (sentToTarget.size() >= BotDecision.CHEER_DAILY_LIMIT_PER_HUMAN) {
            return false;
        }
        // 이 완료 이후 이미 보냈으면 같은 완료에 두 번 보내지 않는다.
        boolean alreadyCheeredSince = sentToTarget.stream()
                .anyMatch(cheer -> !cheer.getCreatedAt().isBefore(completedAt));
        if (alreadyCheeredSince) {
            return false;
        }
        long elapsedMinutes = Duration.between(completedAt, context.now()).toMinutes();
        int delay = BotDecision.cheerDelayMinutes(context.botId(), context.date(), targetUserId, completedAt.getEpochSecond());
        return elapsedMinutes >= delay && elapsedMinutes <= BotDecision.CHEER_WINDOW_END_MINUTES;
    }

    private Map<Long, Instant> latestCompletionByUser(List<Long> userIds, Instant since) {
        BinaryOperator<Instant> later = (a, b) -> a.isAfter(b) ? a : b;
        Map<Long, Instant> latest = new HashMap<>();
        for (UserLatestInstant row : routineLogRepository.findLatestCompletedAtByUserIdsSince(
                userIds, RoutineLogStatus.COMPLETED, since)) {
            latest.merge(row.getUserId(), row.getLatestAt(), later);
        }
        for (UserLatestInstant row : todoRepository.findLatestCompletedAtByUserIdsSince(
                userIds, TodoStatus.COMPLETED, since)) {
            latest.merge(row.getUserId(), row.getLatestAt(), later);
        }
        return latest;
    }

    public void cheer(BotTickContext context, HouseSnapshot house, CheerPlan plan) {
        houseCheerService.cheer(context.botId(), house.houseId(), plan.target().getId(), plan.type());
    }

    // ---- 방명록: 집당 주 1~2회(월·목), 그날 집의 봇 중 1명이 사람 1명 방에 템플릿 문구 ----

    public record GuestbookPlan(HouseMember target, String content) {
    }

    public Optional<GuestbookPlan> guestbookDue(BotTickContext context, HouseSnapshot house) {
        if (!BotDecision.isGuestbookDay(house.houseId(), context.date()) || house.humans().isEmpty()) {
            return Optional.empty();
        }
        List<HouseMember> bots = house.bots().stream()
                .sorted(Comparator.comparingLong(m -> m.getUser().getId()))
                .toList();
        if (bots.isEmpty()) {
            return Optional.empty();
        }
        HouseMember writer = bots.get(BotDecision.pickIndex(house.houseId(), context.date(), 1L, bots.size()));
        if (!writer.getUser().getId().equals(context.botId())) {
            return Optional.empty();
        }
        OptionalInt targetTick = BotDecision.guestbookTick(house.houseId(), context.date(), context.profile());
        if (!BotDecision.isDue(targetTick, context.tick())) {
            return Optional.empty();
        }
        if (roomGuestbookRepository.existsByAuthor_IdAndHouse_IdAndCreatedAtGreaterThanEqual(
                context.botId(), house.houseId(), context.todayStart())) {
            return Optional.empty();
        }
        List<HouseMember> humans = house.humans().stream()
                .sorted(Comparator.comparingLong(HouseMember::getId))
                .toList();
        HouseMember target = humans.get(BotDecision.pickIndex(house.houseId(), context.date(), 2L, humans.size()));
        int completedToday = visibleCompletedCount(target.getUser().getId(), context.date());
        String content = BotGuestbookTemplates.render(
                BotDecision.random(house.houseId(), context.date().toEpochDay(), 10L),
                target.getUser().getNickname(), context.date().getDayOfWeek(), completedToday);
        return Optional.of(new GuestbookPlan(target, content));
    }

    // 방명록에 언급하는 "오늘 완료 개수"는 집 구성원이 볼 수 있는 범위(HOUSE·PUBLIC 카테고리)만 센다 —
    // 타인 완료 열람 계약(HouseMemberActivityService)과 동일. 비공개·친구공개·미분류 루틴 완료는 문구에 새지 않는다.
    int visibleCompletedCount(Long userId, LocalDate date) {
        return routineLogRepository.findVisibleCompletedInPeriod(
                userId, RoutineLogStatus.COMPLETED, date, date, HOUSE_VISIBLE_SCOPES).size();
    }

    public void writeGuestbook(BotTickContext context, HouseSnapshot house, GuestbookPlan plan) {
        guestbookService.write(context.botId(), plan.target().getUser().getId(),
                new GuestbookCreateRequest(house.houseId(), plan.content()));
    }

    // ---- 거미줄: 같은 집 사람 방의 활성 거미줄을 활동 창 틱마다 10% 확률로 청소(보상 3코인은 봇 지갑) ----

    public List<HouseMember> cobwebRoomsDue(BotTickContext context, HouseSnapshot house) {
        Map<Long, HouseMember> humansByUserId = house.humans().stream()
                .collect(Collectors.toMap(m -> m.getUser().getId(), m -> m));
        if (humansByUserId.isEmpty()) {
            return List.of();
        }
        return roomCobwebRepository.findVisibleActiveByRoomUserIdIn(humansByUserId.keySet()).stream()
                .map(RoomCobweb::getRoomUserId)
                .filter(roomUserId -> BotDecision.shouldCleanCobweb(context.botId(), context.date(), context.tick(), roomUserId))
                .map(humansByUserId::get)
                .toList();
    }

    public void cleanCobweb(BotTickContext context, HouseSnapshot house, HouseMember target) {
        roomCobwebService.cleanHouseMemberRoom(context.botId(), house.houseId(), target.getId());
    }

    // ---- 레이아웃: 매주 월요일 활동 창 첫 틱(=그날 아직 방을 안 바꿨을 때) 프리셋 순환 ----

    public record LayoutPlan(int baseRevision, BotRoomPreset preset, List<Long> userItemIds) {
    }

    public Optional<LayoutPlan> layoutRotationDue(BotTickContext context) {
        if (!BotDecision.isLayoutRotationDay(context.date())) {
            return Optional.empty();
        }
        PersonalRoom room = personalRoomRepository.findById(context.botId()).orElse(null);
        if (room == null || !room.getUpdatedAt().isBefore(context.todayStart())) {
            return Optional.empty();
        }
        // item fetch join 조회 — 판정은 트랜잭션 밖에서 하므로 LAZY item 에 접근하는 조회는 쓰지 않는다.
        List<Long> userItemIds = userItemRepository.findInventoryByUserId(context.botId(), null).stream()
                .filter(userItem -> POSITIONED_PLACEMENT_TYPE.equals(userItem.getItem().getPlacementType()))
                .sorted(Comparator.comparingLong(UserItem::getId))
                .map(UserItem::getId)
                .toList();
        if (userItemIds.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new LayoutPlan(room.getLayoutRevision(), BotDecision.presetForWeek(context.date()), userItemIds));
    }

    public void rotateLayout(BotTickContext context, LayoutPlan plan) {
        roomCommandService.updateLayout(context.botId(), new RoomLayoutUpdateRequest(
                plan.baseRevision(), List.of(), plan.preset().placementsFor(plan.userItemIds())));
    }
}
