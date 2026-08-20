package com.triples.rougether.userapi.house;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.goal.entity.Goal;
import com.triples.rougether.domain.goal.repository.GoalRepository;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseGoal;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.repository.HouseGoalRepository;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.house.dto.HouseListResponse;
import com.triples.rougether.userapi.house.service.HouseQueryService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

// 탐색 목록 쿼리 의미 검증 - 삭제 집 제외·goalCode 필터·페이지네이션·최신순·goals 매핑을 실제 DB(H2)로 확인.
@SpringBootTest
@Transactional
class HouseExploreQueryTest {

    @Autowired private HouseQueryService houseQueryService;
    @Autowired private HouseRepository houseRepository;
    @Autowired private HouseMemberRepository houseMemberRepository;
    @Autowired private HouseGoalRepository houseGoalRepository;
    @Autowired private GoalRepository goalRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private House oldest;
    private House middle;
    private House newest;
    private Goal morningGoal;
    private User owner;

    @BeforeEach
    void setUp() {
        owner = userRepository.save(User.signUp("house-explore-test@rougether.dev"));

        // goals 는 마스터 테이블(엔티티에 생성자 없음) - 테스트 픽스처는 SQL 로 적재.
        jdbcTemplate.update(
                "INSERT INTO goals (code, name, sort_order, is_active) VALUES ('morning_routine', '아침 루틴', 1, true)");
        morningGoal = goalRepository.findByIdInAndActiveIsTrue(
                jdbcTemplate.queryForList("SELECT id FROM goals WHERE code = 'morning_routine'", Long.class)).get(0);

        Instant expires = Instant.now().plus(Duration.ofDays(7));
        oldest = houseRepository.save(House.create(owner, "탐색 첫째 집", null, null, 4, "EXPLORE2", expires));
        middle = houseRepository.save(House.create(owner, "탐색 둘째 집", null, null, 4, "EXPLORE3", expires));
        newest = houseRepository.save(House.create(owner, "탐색 셋째 집", null, null, 4, "EXPLORE4", expires));
        houseGoalRepository.save(HouseGoal.create(oldest, morningGoal));
    }

    @Test
    void 최신_생성순으로_페이지네이션해_내려준다() {
        HouseListResponse first = houseQueryService.explore(owner.getId(), 0, 2, null, false);

        assertThat(first.totalElements()).isGreaterThanOrEqualTo(3);
        assertThat(first.items()).hasSize(2);
        // createdAt desc, id desc - 가장 나중에 만든 집이 먼저
        assertThat(first.items().get(0).houseId()).isEqualTo(newest.getId());
        assertThat(first.items().get(1).houseId()).isEqualTo(middle.getId());
        assertThat(first.page()).isZero();
        assertThat(first.size()).isEqualTo(2);
    }

    @Test
    void 삭제된_집은_목록에서_제외한다() {
        jdbcTemplate.update("UPDATE house SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", middle.getId());

        HouseListResponse response = houseQueryService.explore(owner.getId(), 0, 20, null, false);

        assertThat(response.items()).extracting(HouseListResponse.HouseSummary::houseId)
                .contains(oldest.getId(), newest.getId())
                .doesNotContain(middle.getId());
    }

    @Test
    void 비공개_집은_탐색과_goalCode_필터에서_제외한다() {
        House privateHouse = houseRepository.save(House.createPrivate(
                owner, "가입 기본 집", null, null, 4, "PRIVATE2",
                Instant.now().plus(Duration.ofDays(7))));
        houseGoalRepository.save(HouseGoal.create(privateHouse, morningGoal));

        HouseListResponse all = houseQueryService.explore(owner.getId(), 0, 20, null, false);
        HouseListResponse filtered = houseQueryService.explore(
                owner.getId(), 0, 20, "morning_routine", false);
        HouseListResponse excludingJoined = houseQueryService.explore(owner.getId(), 0, 20, null, true);
        HouseListResponse filteredExcludingJoined = houseQueryService.explore(
                owner.getId(), 0, 20, "morning_routine", true);

        assertThat(all.items()).extracting(HouseListResponse.HouseSummary::houseId)
                .doesNotContain(privateHouse.getId());
        assertThat(filtered.items()).extracting(HouseListResponse.HouseSummary::houseId)
                .contains(oldest.getId())
                .doesNotContain(privateHouse.getId());
        assertThat(excludingJoined.items()).extracting(HouseListResponse.HouseSummary::houseId)
                .doesNotContain(privateHouse.getId());
        assertThat(filteredExcludingJoined.items()).extracting(HouseListResponse.HouseSummary::houseId)
                .contains(oldest.getId())
                .doesNotContain(privateHouse.getId());
    }

    @Test
    void goalCode로_필터링한다() {
        HouseListResponse response = houseQueryService.explore(
                owner.getId(), 0, 20, "morning_routine", false);

        assertThat(response.items()).extracting(HouseListResponse.HouseSummary::houseId)
                .containsExactly(oldest.getId());
        assertThat(response.totalElements()).isEqualTo(1);
    }

    @Test
    void 목록_항목에_goals가_매핑된다() {
        HouseListResponse response = houseQueryService.explore(owner.getId(), 0, 20, null, false);

        HouseListResponse.HouseSummary withGoal = response.items().stream()
                .filter(item -> item.houseId().equals(oldest.getId())).findFirst().orElseThrow();
        assertThat(withGoal.goals()).hasSize(1);
        assertThat(withGoal.goals().get(0).code()).isEqualTo("morning_routine");
        assertThat(withGoal.goals().get(0).name()).isEqualTo("아침 루틴");
        assertThat(withGoal.goals().get(0).goalId()).isEqualTo(morningGoal.getId());

        HouseListResponse.HouseSummary withoutGoal = response.items().stream()
                .filter(item -> item.houseId().equals(newest.getId())).findFirst().orElseThrow();
        assertThat(withoutGoal.goals()).isEmpty();
    }

    @Test
    void excludeJoined면_가입한_집을_제외한다() {
        houseMemberRepository.save(HouseMember.create(middle, owner, HouseMemberRole.MEMBER));

        HouseListResponse excluded = houseQueryService.explore(owner.getId(), 0, 20, null, true);

        assertThat(excluded.items()).extracting(HouseListResponse.HouseSummary::houseId)
                .contains(oldest.getId(), newest.getId())
                .doesNotContain(middle.getId());

        // 기본값(false)은 기존과 동일하게 가입한 집도 포함한다.
        HouseListResponse included = houseQueryService.explore(owner.getId(), 0, 20, null, false);
        assertThat(included.items()).extracting(HouseListResponse.HouseSummary::houseId)
                .contains(middle.getId());
    }

    @Test
    void excludeJoined는_탈퇴한_집은_제외하지_않는다() {
        HouseMember membership = houseMemberRepository.save(
                HouseMember.create(middle, owner, HouseMemberRole.MEMBER));
        membership.leave();

        HouseListResponse response = houseQueryService.explore(owner.getId(), 0, 20, null, true);

        assertThat(response.items()).extracting(HouseListResponse.HouseSummary::houseId)
                .contains(middle.getId());
    }

    @Test
    void excludeJoined는_goalCode_필터와_함께_적용된다() {
        houseMemberRepository.save(HouseMember.create(oldest, owner, HouseMemberRole.MEMBER));

        HouseListResponse response = houseQueryService.explore(
                owner.getId(), 0, 20, "morning_routine", true);

        assertThat(response.items()).isEmpty();
        assertThat(response.totalElements()).isZero();
    }

    @Test
    void 없는_goalCode면_빈_목록이다() {
        HouseListResponse response = houseQueryService.explore(
                owner.getId(), 0, 20, "no_such_goal", false);

        assertThat(response.items()).isEmpty();
        assertThat(response.totalElements()).isZero();
    }
}
