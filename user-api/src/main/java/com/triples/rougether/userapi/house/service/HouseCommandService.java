package com.triples.rougether.userapi.house.service;

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
import com.triples.rougether.userapi.house.dto.HouseCreateRequest;
import com.triples.rougether.userapi.house.dto.HouseCreateResponse;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import com.triples.rougether.userapi.house.support.InviteCodeGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 집 생성. 집 + OWNER 멤버 + 목표 연결을 단일 트랜잭션으로 저장하고 초대코드를 발급한다.
@Service
public class HouseCommandService {

    private static final int DEFAULT_MAX_MEMBERS = 4;
    private static final Duration INVITE_CODE_TTL = Duration.ofDays(7);

    private final HouseRepository houseRepository;
    private final HouseMemberRepository houseMemberRepository;
    private final HouseGoalRepository houseGoalRepository;
    private final GoalRepository goalRepository;
    private final UserRepository userRepository;
    private final InviteCodeGenerator inviteCodeGenerator;

    public HouseCommandService(HouseRepository houseRepository,
                               HouseMemberRepository houseMemberRepository,
                               HouseGoalRepository houseGoalRepository,
                               GoalRepository goalRepository,
                               UserRepository userRepository,
                               InviteCodeGenerator inviteCodeGenerator) {
        this.houseRepository = houseRepository;
        this.houseMemberRepository = houseMemberRepository;
        this.houseGoalRepository = houseGoalRepository;
        this.goalRepository = goalRepository;
        this.userRepository = userRepository;
        this.inviteCodeGenerator = inviteCodeGenerator;
    }

    @Transactional
    public HouseCreateResponse create(Long userId, HouseCreateRequest request) {
        List<Long> goalIds = request.goalIds().stream().distinct().toList();
        List<Goal> goals = goalRepository.findByIdInAndActiveIsTrue(goalIds);
        if (goals.size() != goalIds.size()) {
            throw new BusinessException(HouseErrorCode.HOUSE_GOAL_INVALID);
        }

        User owner = userRepository.getReferenceById(userId);
        int maxMembers = request.maxMembers() == null ? DEFAULT_MAX_MEMBERS : request.maxMembers();
        House house = houseRepository.save(House.create(
                owner, request.name(), request.description(), request.coverImageKey(),
                maxMembers, inviteCodeGenerator.generate(), Instant.now().plus(INVITE_CODE_TTL)));

        houseMemberRepository.save(HouseMember.create(house, owner, HouseMemberRole.OWNER));
        houseGoalRepository.saveAll(goals.stream().map(goal -> HouseGoal.create(house, goal)).toList());

        return new HouseCreateResponse(house.getId(), userId, house.getInviteCode(), house.getInviteExpiresAt());
    }
}
