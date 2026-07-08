package com.triples.rougether.userapi.house;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.common.error.ErrorCode;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.room.entity.PersonalRoom;
import com.triples.rougether.domain.room.repository.PersonalRoomRepository;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Category;
import com.triples.rougether.domain.routine.entity.PrivacyScope;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLog;
import com.triples.rougether.domain.routine.entity.Todo;
import com.triples.rougether.domain.routine.entity.TodoStatus;
import com.triples.rougether.domain.routine.repository.CategoryRepository;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.userapi.house.dto.HouseMemberDayResponse;
import com.triples.rougether.userapi.house.dto.HouseMemberRoutineCompletionListResponse;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import com.triples.rougether.userapi.house.service.HouseMemberActivityService;
import com.triples.rougether.userapi.room.dto.RoomResponse;
import com.triples.rougether.userapi.room.error.RoomErrorCode;
import com.triples.rougether.userapi.routine.dto.RepeatDays;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

// 집 멤버 활동 열람(방/루틴/완료 내역) 회귀. 같은 집 guard + 카테고리 공개 범위 필터 + 기간 필터를 실제 DB 로 검증.
@SpringBootTest
@Transactional
class HouseMemberActivityIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired private HouseMemberActivityService activityService;
    @Autowired private UserRepository userRepository;
    @Autowired private HouseRepository houseRepository;
    @Autowired private HouseMemberRepository houseMemberRepository;
    @Autowired private PersonalRoomRepository personalRoomRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private RoutineRepository routineRepository;
    @Autowired private RoutineLogRepository routineLogRepository;
    @Autowired private TodoRepository todoRepository;
    @PersistenceContext private EntityManager em;

    private record Fixture(User viewer, User target, House house,
                           HouseMember viewerMembership, HouseMember targetMembership) {

        Long targetMembershipId() {
            return targetMembership.getId();
        }
    }

    private Fixture fixture() {
        User viewer = userRepository.save(User.signUp("activity-viewer@rougether.dev"));
        User target = userRepository.save(User.signUp("activity-target@rougether.dev"));
        House house = houseRepository.save(House.create(
                viewer, "활동 하우스", null, null, 4, "ACT12345", Instant.now().plus(Duration.ofDays(7))));
        HouseMember viewerMembership = houseMemberRepository.save(
                HouseMember.create(house, viewer, HouseMemberRole.OWNER));
        HouseMember targetMembership = houseMemberRepository.save(
                HouseMember.create(house, target, HouseMemberRole.MEMBER));
        house.increaseMemberCount();
        return new Fixture(viewer, target, house, viewerMembership, targetMembership);
    }

    private Routine routineIn(User owner, PrivacyScope visibility, String title) {
        Category category = categoryRepository.save(Category.create(
                owner, title + " 카테고리", null, null, 0, visibility));
        return saveRoutine(owner, category, title);
    }

    private Routine saveRoutine(User owner, Category category, String title) {
        Routine saved = routineRepository.save(Routine.create(
                owner, category, title, AuthType.CHECK, "DAILY", null, null, null, null));
        saved.assignOriginToSelf();
        return saved;
    }

    private void completeOn(Routine routine, LocalDate date) {
        routineLogRepository.save(RoutineLog.complete(
                routine, date, date.atStartOfDay(KST).toInstant(), CurrencyType.COIN, 10));
    }

    private static ErrorCode errorCodeOf(Throwable e) {
        return ((BusinessException) e).getErrorCode();
    }

    // --- 방 ---

    @Test
    void 같은_집_멤버의_방을_조회한다() {
        Fixture f = fixture();
        personalRoomRepository.save(PersonalRoom.create(f.target()));

        RoomResponse response = activityService.getMemberRoom(
                f.viewer().getId(), f.house().getId(), f.targetMembershipId());

        assertThat(response.roomUserId()).isEqualTo(f.target().getId());
        assertThat(response.growthLevel()).isZero();
        assertThat(response.slots()).isEmpty();
    }

    @Test
    void 본인도_같은_API_로_방을_조회할_수_있다() {
        Fixture f = fixture();
        personalRoomRepository.save(PersonalRoom.create(f.viewer()));

        RoomResponse response = activityService.getMemberRoom(
                f.viewer().getId(), f.house().getId(), f.viewerMembership().getId());

        assertThat(response.roomUserId()).isEqualTo(f.viewer().getId());
    }

    @Test
    void 대상이_방을_아직_만들지_않았으면_404() {
        Fixture f = fixture();

        assertThatThrownBy(() -> activityService.getMemberRoom(
                f.viewer().getId(), f.house().getId(), f.targetMembershipId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo(RoomErrorCode.ROOM_NOT_FOUND));
    }

    // --- guard ---

    @Test
    void 요청자가_집_멤버가_아니면_403() {
        Fixture f = fixture();
        User stranger = userRepository.save(User.signUp("activity-stranger@rougether.dev"));

        assertThatThrownBy(() -> activityService.getMemberDay(
                stranger.getId(), f.house().getId(), f.targetMembershipId(), null))
                .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo(HouseErrorCode.HOUSE_NOT_MEMBER));
    }

    @Test
    void 조회_대상_membership_이_없거나_다른_집이면_404() {
        Fixture f = fixture();
        // 다른 집의 membership 을 이 집 경로로 조회하는 우회 시도
        User outsider = userRepository.save(User.signUp("activity-outsider@rougether.dev"));
        House otherHouse = houseRepository.save(House.create(
                outsider, "다른 하우스", null, null, 4, "OTH12345", Instant.now().plus(Duration.ofDays(7))));
        HouseMember otherMembership = houseMemberRepository.save(
                HouseMember.create(otherHouse, outsider, HouseMemberRole.OWNER));

        assertThatThrownBy(() -> activityService.getMemberDay(
                f.viewer().getId(), f.house().getId(), 999999L, null))
                .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo(HouseErrorCode.HOUSE_MEMBER_NOT_FOUND));
        assertThatThrownBy(() -> activityService.getMemberDay(
                f.viewer().getId(), f.house().getId(), otherMembership.getId(), null))
                .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo(HouseErrorCode.HOUSE_MEMBER_NOT_FOUND));
    }

    @Test
    void 없는_집이면_404() {
        Fixture f = fixture();

        assertThatThrownBy(() -> activityService.getMemberRoom(
                f.viewer().getId(), 999999L, f.targetMembershipId()))
                .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo(HouseErrorCode.HOUSE_NOT_FOUND));
    }

    // --- 루틴 공개 범위 ---

    @Test
    void 루틴은_HOUSE_또는_PUBLIC_카테고리만_보인다() {
        Fixture f = fixture();
        routineIn(f.target(), PrivacyScope.PRIVATE, "비공개 루틴");
        routineIn(f.target(), PrivacyScope.FRIENDS, "친한친구 루틴");
        routineIn(f.target(), PrivacyScope.HOUSE, "집 공개 루틴");
        routineIn(f.target(), PrivacyScope.PUBLIC, "전체 공개 루틴");
        saveRoutine(f.target(), null, "미분류 루틴");

        HouseMemberDayResponse response = activityService.getMemberDay(
                f.viewer().getId(), f.house().getId(), f.targetMembershipId(), null);

        assertThat(response.routines()).extracting("title")
                .containsExactlyInAnyOrder("집 공개 루틴", "전체 공개 루틴");
    }

    @Test
    void 루틴은_그날_반복_대상만_완료_여부와_함께_보인다() {
        Fixture f = fixture();
        LocalDate monday = LocalDate.of(2026, 7, 6); // 월요일
        Category category = categoryRepository.save(Category.create(
                f.target(), "요일 카테고리", null, null, 0, PrivacyScope.HOUSE));
        Routine daily = routineRepository.save(Routine.create(f.target(), category, "매일 루틴",
                AuthType.CHECK, "DAILY", null, null, null, null));
        daily.assignOriginToSelf();
        Routine mon = routineRepository.save(Routine.create(f.target(), category, "월요일 루틴",
                AuthType.CHECK, "WEEKLY", new RepeatDays(List.of("MON")).toJson(),
                null, null, null));
        mon.assignOriginToSelf();
        Routine tue = routineRepository.save(Routine.create(f.target(), category, "화요일 루틴",
                AuthType.CHECK, "WEEKLY", new RepeatDays(List.of("TUE")).toJson(),
                null, null, null));
        tue.assignOriginToSelf();
        Routine ended = routineRepository.save(Routine.create(f.target(), category, "종료된 루틴",
                AuthType.CHECK, "DAILY", null, null, null, monday.minusDays(1)));
        ended.assignOriginToSelf();
        completeOn(daily, monday);
        // 기준일(과거)에 이미 존재했던 루틴들로 만들기 위해 생성일을 당김
        for (Routine routine : List.of(daily, mon, tue, ended)) {
            backdateCreatedAt(routine.getId(), 10);
        }

        HouseMemberDayResponse response = activityService.getMemberDay(
                f.viewer().getId(), f.house().getId(), f.targetMembershipId(), monday);

        assertThat(response.date()).isEqualTo(monday);
        assertThat(response.routines()).extracting("title", "completed")
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("매일 루틴", true),
                        org.assertj.core.groups.Tuple.tuple("월요일 루틴", false));
    }

    @Test
    void 과거_날짜는_그날_유효했던_버전으로_재구성되고_완료가_정확하다() {
        Fixture f = fixture();
        LocalDate yesterday = LocalDate.now(KST).minusDays(1);
        Category open = categoryRepository.save(Category.create(
                f.target(), "공개 카테고리", null, null, 0, PrivacyScope.HOUSE));
        // ① 옛 버전: 이틀 전 생성 → 어제 완료 → 오늘 닫힘(스케줄 수정으로 버전 분기 상황)
        Routine oldVersion = saveRoutine(f.target(), open, "옛 버전 루틴");
        completeOn(oldVersion, yesterday);
        oldVersion.softDelete(Instant.now());
        backdateCreatedAt(oldVersion.getId(), 2);
        // ② 오늘 만든 루틴 — 어제 화면에는 없어야 함
        saveRoutine(f.target(), open, "오늘 만든 루틴");
        // ③ 비공개 옛 루틴 — 어제 완료 log 가 있어도 공개 범위로 제외
        Routine hidden = routineIn(f.target(), PrivacyScope.PRIVATE, "비공개 루틴");
        completeOn(hidden, yesterday);
        backdateCreatedAt(hidden.getId(), 2);

        HouseMemberDayResponse response = activityService.getMemberDay(
                f.viewer().getId(), f.house().getId(), f.targetMembershipId(), yesterday);

        assertThat(response.routines()).extracting("title", "completed")
                .containsExactly(org.assertj.core.groups.Tuple.tuple("옛 버전 루틴", true));
    }

    // created_at 은 auditing 이 now 로 채우고 updatable=false 라 JPA 로 못 바꿈 → 네이티브로 과거로 당김
    private void backdateCreatedAt(Long routineId, int days) {
        em.flush();
        em.createNativeQuery(
                "update routines set created_at = created_at - interval " + days
                        + " day where id = " + routineId).executeUpdate();
        em.clear();
    }

    // --- 투두 ---

    @Test
    void 투두는_그날_마감분만_공개_범위로_필터링되어_보인다() {
        Fixture f = fixture();
        LocalDate today = LocalDate.now(KST);
        Category open = categoryRepository.save(Category.create(
                f.target(), "공개 카테고리", null, null, 0, PrivacyScope.HOUSE));
        Category closed = categoryRepository.save(Category.create(
                f.target(), "비공개 카테고리", null, null, 0, PrivacyScope.PRIVATE));
        Todo done = todoRepository.save(Todo.create(f.target(), open, "완료한 투두", null, today));
        done.complete(CurrencyType.COIN, 10, Instant.now());
        todoRepository.save(Todo.create(f.target(), open, "남은 투두", null, today));
        todoRepository.save(Todo.create(f.target(), open, "내일 투두", null, today.plusDays(1)));
        todoRepository.save(Todo.create(f.target(), closed, "비공개 투두", null, today));
        todoRepository.save(Todo.create(f.target(), null, "미분류 투두", null, today));

        HouseMemberDayResponse response = activityService.getMemberDay(
                f.viewer().getId(), f.house().getId(), f.targetMembershipId(), null);

        assertThat(response.todos()).extracting("title", "status")
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("완료한 투두", TodoStatus.COMPLETED),
                        org.assertj.core.groups.Tuple.tuple("남은 투두", TodoStatus.PENDING));
    }

    // --- 완료 내역 ---

    @Test
    void 완료_내역은_기간과_공개_범위로_필터링된다() {
        Fixture f = fixture();
        LocalDate today = LocalDate.now(KST);
        Routine visible = routineIn(f.target(), PrivacyScope.HOUSE, "집 공개 루틴");
        Routine hidden = routineIn(f.target(), PrivacyScope.PRIVATE, "비공개 루틴");
        completeOn(visible, today);
        completeOn(visible, today.minusDays(3));
        completeOn(visible, today.minusDays(20)); // 기본 14일 창 밖
        completeOn(hidden, today);                // 공개 범위 밖

        HouseMemberRoutineCompletionListResponse response = activityService.getMemberRoutineCompletions(
                f.viewer().getId(), f.house().getId(), f.targetMembershipId(), null, null);

        assertThat(response.to()).isEqualTo(today);
        assertThat(response.from()).isEqualTo(today.minusDays(13));
        assertThat(response.items()).extracting("routineDate")
                .containsExactly(today, today.minusDays(3)); // 최신 날짜 먼저
        assertThat(response.items()).allSatisfy(item ->
                assertThat(item.title()).isEqualTo("집 공개 루틴"));
    }

    @Test
    void 완료_내역은_지정한_from_to_구간만_반환한다() {
        Fixture f = fixture();
        LocalDate today = LocalDate.now(KST);
        Routine visible = routineIn(f.target(), PrivacyScope.PUBLIC, "전체 공개 루틴");
        completeOn(visible, today);
        completeOn(visible, today.minusDays(20));
        completeOn(visible, today.minusDays(40));

        HouseMemberRoutineCompletionListResponse response = activityService.getMemberRoutineCompletions(
                f.viewer().getId(), f.house().getId(), f.targetMembershipId(),
                today.minusDays(30), today.minusDays(10));

        assertThat(response.items()).extracting("routineDate")
                .containsExactly(today.minusDays(20));
    }

    @Test
    void 완료_내역_기간은_하루짜리와_92일짜리까지_허용된다() {
        Fixture f = fixture();
        LocalDate today = LocalDate.now(KST);
        Routine visible = routineIn(f.target(), PrivacyScope.HOUSE, "집 공개 루틴");
        completeOn(visible, today);

        // from=to (하루)
        assertThat(activityService.getMemberRoutineCompletions(
                f.viewer().getId(), f.house().getId(), f.targetMembershipId(), today, today)
                .items()).hasSize(1);
        // 정확히 92일 창 (from = to-91)
        assertThat(activityService.getMemberRoutineCompletions(
                f.viewer().getId(), f.house().getId(), f.targetMembershipId(), today.minusDays(91), today)
                .items()).hasSize(1);
    }

    @Test
    void 완료_내역_기간이_뒤집히거나_92일을_넘으면_400() {
        Fixture f = fixture();
        LocalDate today = LocalDate.now(KST);

        assertThatThrownBy(() -> activityService.getMemberRoutineCompletions(
                f.viewer().getId(), f.house().getId(), f.targetMembershipId(), today, today.minusDays(1)))
                .satisfies(e -> assertThat(errorCodeOf(e))
                        .isEqualTo(HouseErrorCode.HOUSE_ACTIVITY_PERIOD_INVALID));
        assertThatThrownBy(() -> activityService.getMemberRoutineCompletions(
                f.viewer().getId(), f.house().getId(), f.targetMembershipId(), today.minusDays(92), today))
                .satisfies(e -> assertThat(errorCodeOf(e))
                        .isEqualTo(HouseErrorCode.HOUSE_ACTIVITY_PERIOD_INVALID));
    }
}
