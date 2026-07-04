package com.triples.rougether.userapi.house;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.triples.rougether.domain.goal.entity.Goal;
import com.triples.rougether.domain.goal.repository.GoalRepository;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.house.repository.HouseGoalRepository;
import com.triples.rougether.userapi.house.dto.HouseCreateRequest;
import com.triples.rougether.userapi.house.service.HouseCommandService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

// 집 생성이 단일 트랜잭션인지 — 마지막 쓰기(house_goals)가 실패하면 앞선 house/멤버 저장이 롤백돼야 한다.
@SpringBootTest
class HouseCreateTransactionTest {

    private static final String HOUSE_NAME = "트랜잭션 검증 하우스";

    @Autowired private HouseCommandService houseCommandService;
    @Autowired private HouseRepository houseRepository;
    @Autowired private HouseMemberRepository houseMemberRepository;
    @Autowired private UserRepository userRepository;

    @MockitoBean private GoalRepository goalRepository;
    @MockitoBean private HouseGoalRepository houseGoalRepository;

    private User user;

    @AfterEach
    void cleanUp() {
        if (user != null) {
            userRepository.deleteById(user.getId());
        }
    }

    @Test
    void 목표_연결_저장이_실패하면_집과_멤버_저장도_롤백된다() {
        user = userRepository.save(User.signUp("house-tx-test@rougether.dev"));
        given(goalRepository.findByIdInAndActiveIsTrue(List.of(1L))).willReturn(List.of(mock(Goal.class)));
        given(houseGoalRepository.saveAll(anyList())).willThrow(new RuntimeException("house_goals 저장 실패"));

        assertThatThrownBy(() -> houseCommandService.create(user.getId(),
                new HouseCreateRequest(HOUSE_NAME, null, null, null, List.of(1L))))
                .isInstanceOf(RuntimeException.class);

        assertThat(houseRepository.findAll())
                .noneMatch(house -> HOUSE_NAME.equals(house.getName()));
        assertThat(houseMemberRepository.findAll())
                .noneMatch(member -> member.getUser().getId().equals(user.getId()));
    }
}
