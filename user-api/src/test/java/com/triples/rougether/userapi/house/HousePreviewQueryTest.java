package com.triples.rougether.userapi.house;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.common.error.BusinessException;
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
import com.triples.rougether.userapi.house.dto.HousePreviewDetailResponse;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import com.triples.rougether.userapi.house.service.HouseMemberCommandService;
import com.triples.rougether.userapi.house.service.HouseQueryService;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

// 비구성원 집 미리보기(#169) - 전체공개 조회·isMember/isFull 판정·KICKED 허용·삭제 404 를 실제 DB 로 검증.
@SpringBootTest
@Transactional
class HousePreviewQueryTest {

    @Autowired private HouseQueryService houseQueryService;
    @Autowired private HouseMemberCommandService houseMemberCommandService;
    @Autowired private HouseRepository houseRepository;
    @Autowired private HouseMemberRepository houseMemberRepository;
    @Autowired private HouseGoalRepository houseGoalRepository;
    @Autowired private GoalRepository goalRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User owner;
    private User stranger;
    private House house;

    @BeforeEach
    void setUp() {
        owner = userRepository.save(User.signUp("preview-owner@rougether.dev"));
        stranger = userRepository.save(User.signUp("preview-stranger@rougether.dev"));
        house = houseRepository.save(House.create(
                owner, "미리보기 하우스", "같이 아침 루틴 지켜요", "house/preview.png",
                2, "PREVIEW2", Instant.now().plus(Duration.ofDays(7))));
        houseMemberRepository.save(HouseMember.create(house, owner, HouseMemberRole.OWNER));

        // goals 는 마스터 테이블(엔티티에 생성자 없음) - 테스트 픽스처는 SQL 로 적재.
        jdbcTemplate.update(
                "INSERT INTO goals (code, name, sort_order, is_active) VALUES ('preview_goal', '미리보기 목표', 1, true)");
        Goal goal = goalRepository.findByIdInAndActiveIsTrue(
                jdbcTemplate.queryForList("SELECT id FROM goals WHERE code = 'preview_goal'", Long.class)).get(0);
        houseGoalRepository.save(HouseGoal.create(house, goal));
    }

    @Test
    void 비구성원도_상세와_동일한_집_정보를_조회한다() {
        HousePreviewDetailResponse response = houseQueryService.getPreview(stranger.getId(), house.getId());

        assertThat(response.houseId()).isEqualTo(house.getId());
        assertThat(response.name()).isEqualTo("미리보기 하우스");
        assertThat(response.description()).isEqualTo("같이 아침 루틴 지켜요");
        assertThat(response.coverImageKey()).isEqualTo("house/preview.png");
        assertThat(response.currentMemberCount()).isEqualTo(1);
        assertThat(response.maxMembers()).isEqualTo(2);
        assertThat(response.level()).isZero();
        assertThat(response.goals()).hasSize(1);
        assertThat(response.goals().get(0).code()).isEqualTo("preview_goal");
        assertThat(response.isMember()).isFalse();
        assertThat(response.isFull()).isFalse();
    }

    @Test
    void 활성_구성원이면_isMember가_true다() {
        HousePreviewDetailResponse response = houseQueryService.getPreview(owner.getId(), house.getId());

        assertThat(response.isMember()).isTrue();
    }

    @Test
    void 정원이_차면_isFull이_true다() {
        HouseMember joined = houseMemberRepository.save(
                HouseMember.create(house, stranger, HouseMemberRole.MEMBER));
        house.increaseMemberCount(); // 참여 확정과 동일 규칙으로 카운트 반영 (max 2 도달)

        HousePreviewDetailResponse response = houseQueryService.getPreview(stranger.getId(), house.getId());

        assertThat(joined.isActive()).isTrue();
        assertThat(response.isFull()).isTrue();
        assertThat(response.isMember()).isTrue();
    }

    @Test
    void KICKED_이력자도_미리보기는_조회할_수_있고_isMember는_false다() {
        HouseMember member = houseMemberRepository.save(
                HouseMember.create(house, stranger, HouseMemberRole.MEMBER));
        house.increaseMemberCount();
        houseMemberCommandService.kick(owner.getId(), house.getId(), member.getId());

        HousePreviewDetailResponse response = houseQueryService.getPreview(stranger.getId(), house.getId());

        assertThat(response.isMember()).isFalse();
        assertThat(response.currentMemberCount()).isEqualTo(1);
    }

    @Test
    void 삭제된_집은_404다() {
        house.softDelete();

        assertThatThrownBy(() -> houseQueryService.getPreview(stranger.getId(), house.getId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_NOT_FOUND));
    }

    @Test
    void 기존_상세_API의_구성원_전용_계약은_변하지_않는다() {
        assertThatThrownBy(() -> houseQueryService.getHouseDetail(stranger.getId(), house.getId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_NOT_MEMBER));
    }
}
