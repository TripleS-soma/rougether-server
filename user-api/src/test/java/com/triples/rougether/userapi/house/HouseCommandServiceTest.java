package com.triples.rougether.userapi.house;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.goal.entity.Goal;
import com.triples.rougether.domain.goal.repository.GoalRepository;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseGoal;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.entity.HouseMemberStatus;
import com.triples.rougether.domain.house.repository.HouseGoalRepository;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.house.dto.HouseCreateRequest;
import com.triples.rougether.userapi.house.dto.HouseCreateResponse;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import com.triples.rougether.userapi.house.service.HouseCoverImageCatalog;
import com.triples.rougether.userapi.house.service.HouseCommandService;
import com.triples.rougether.userapi.house.support.InviteCodeGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HouseCommandServiceTest {

    @Mock private HouseRepository houseRepository;
    @Mock private HouseMemberRepository houseMemberRepository;
    @Mock private HouseGoalRepository houseGoalRepository;
    @Mock private GoalRepository goalRepository;
    @Mock private UserRepository userRepository;
    @Mock private InviteCodeGenerator inviteCodeGenerator;
    @Mock private HouseCoverImageCatalog houseCoverImageCatalog;
    @InjectMocks private HouseCommandService houseCommandService;

    private HouseCreateRequest request(Integer maxMembers, List<Long> goalIds) {
        return new HouseCreateRequest("아침 루틴 하우스", "설명", "house/cover.png", maxMembers, goalIds);
    }

    private Goal goal(Long id) {
        Goal goal = mock(Goal.class);
        return goal;
    }

    @Test
    void 집을_생성하면_OWNER_멤버와_목표가_함께_저장된다() {
        Goal goal1 = goal(1L);
        Goal goal2 = goal(2L);
        when(goalRepository.findByIdInAndActiveIsTrue(List.of(1L, 2L))).thenReturn(List.of(goal1, goal2));
        when(userRepository.getReferenceById(7L)).thenReturn(mock(User.class));
        when(inviteCodeGenerator.generate()).thenReturn("ABCD2345");
        when(houseRepository.save(any(House.class))).thenAnswer(inv -> inv.getArgument(0));

        HouseCreateResponse response = houseCommandService.create(7L, request(6, List.of(1L, 2L)));

        verify(houseCoverImageCatalog).validatePublished("house/cover.png");
        ArgumentCaptor<House> houseCaptor = ArgumentCaptor.forClass(House.class);
        verify(houseRepository).save(houseCaptor.capture());
        House saved = houseCaptor.getValue();
        assertThat(saved.getName()).isEqualTo("아침 루틴 하우스");
        assertThat(saved.getMaxMembers()).isEqualTo(6);
        assertThat(saved.getCurrentMemberCount()).isEqualTo(1);
        assertThat(saved.getLevel()).isZero();
        assertThat(saved.getInviteCode()).isEqualTo("ABCD2345");
        // 초대코드 만료 7일
        assertThat(saved.getInviteExpiresAt())
                .isBetween(Instant.now().plus(Duration.ofDays(7)).minusSeconds(60),
                        Instant.now().plus(Duration.ofDays(7)).plusSeconds(60));

        ArgumentCaptor<HouseMember> memberCaptor = ArgumentCaptor.forClass(HouseMember.class);
        verify(houseMemberRepository).save(memberCaptor.capture());
        assertThat(memberCaptor.getValue().getRole()).isEqualTo(HouseMemberRole.OWNER);
        assertThat(memberCaptor.getValue().getStatus()).isEqualTo(HouseMemberStatus.ACTIVE);

        ArgumentCaptor<List<HouseGoal>> goalsCaptor = ArgumentCaptor.forClass(List.class);
        verify(houseGoalRepository).saveAll(goalsCaptor.capture());
        assertThat(goalsCaptor.getValue()).hasSize(2);
        // 요청한 goal 이 그대로 연결됐는지까지 확인
        assertThat(goalsCaptor.getValue()).extracting(HouseGoal::getGoal).containsExactly(goal1, goal2);

        assertThat(response.ownerUserId()).isEqualTo(7L);
        assertThat(response.inviteCode()).isEqualTo("ABCD2345");
    }

    @Test
    void maxMembers_미지정이면_기본_4를_적용한다() {
        when(goalRepository.findByIdInAndActiveIsTrue(List.of(1L))).thenReturn(List.of(goal(1L)));
        when(userRepository.getReferenceById(7L)).thenReturn(mock(User.class));
        when(inviteCodeGenerator.generate()).thenReturn("ABCD2345");
        when(houseRepository.save(any(House.class))).thenAnswer(inv -> inv.getArgument(0));

        houseCommandService.create(7L, request(null, List.of(1L)));

        ArgumentCaptor<House> captor = ArgumentCaptor.forClass(House.class);
        verify(houseRepository).save(captor.capture());
        assertThat(captor.getValue().getMaxMembers()).isEqualTo(4);
    }

    @Test
    void 없는_목표나_비활성_목표가_섞여있으면_거부한다() {
        // 요청 2개 중 활성 goal 은 1개만 조회됨
        when(goalRepository.findByIdInAndActiveIsTrue(List.of(1L, 99L))).thenReturn(List.of(goal(1L)));

        assertThatThrownBy(() -> houseCommandService.create(7L, request(null, List.of(1L, 99L))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_GOAL_INVALID));
        verify(houseRepository, never()).save(any());
        verify(houseMemberRepository, never()).save(any());
        verify(houseGoalRepository, never()).saveAll(anyList());
    }

    @Test
    void 중복된_goalId는_한_번만_연결한다() {
        when(goalRepository.findByIdInAndActiveIsTrue(List.of(1L))).thenReturn(List.of(goal(1L)));
        when(userRepository.getReferenceById(7L)).thenReturn(mock(User.class));
        when(inviteCodeGenerator.generate()).thenReturn("ABCD2345");
        when(houseRepository.save(any(House.class))).thenAnswer(inv -> inv.getArgument(0));

        houseCommandService.create(7L, request(null, List.of(1L, 1L)));

        ArgumentCaptor<List<HouseGoal>> captor = ArgumentCaptor.forClass(List.class);
        verify(houseGoalRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
    }
}
