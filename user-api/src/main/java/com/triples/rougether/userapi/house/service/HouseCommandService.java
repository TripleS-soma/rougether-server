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
import com.triples.rougether.userapi.house.dto.HouseUpdateRequest;
import com.triples.rougether.userapi.house.dto.HouseUpdateResponse;
import com.triples.rougether.userapi.house.dto.InviteCodeResponse;
import com.triples.rougether.userapi.global.text.BannedWordChecker;
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
    private static final String ONBOARDING_STARTER_HOUSE_NAME = "나의 집";
    private static final Duration INVITE_CODE_TTL = Duration.ofDays(7);

    private final HouseRepository houseRepository;
    private final HouseMemberRepository houseMemberRepository;
    private final HouseGoalRepository houseGoalRepository;
    private final GoalRepository goalRepository;
    private final UserRepository userRepository;
    private final InviteCodeGenerator inviteCodeGenerator;
    private final HouseCoverImageCatalog houseCoverImageCatalog;
    private final BannedWordChecker bannedWordChecker;

    public HouseCommandService(HouseRepository houseRepository,
                               HouseMemberRepository houseMemberRepository,
                               HouseGoalRepository houseGoalRepository,
                               GoalRepository goalRepository,
                               UserRepository userRepository,
                               InviteCodeGenerator inviteCodeGenerator,
                               HouseCoverImageCatalog houseCoverImageCatalog,
                               BannedWordChecker bannedWordChecker) {
        this.houseRepository = houseRepository;
        this.houseMemberRepository = houseMemberRepository;
        this.houseGoalRepository = houseGoalRepository;
        this.goalRepository = goalRepository;
        this.userRepository = userRepository;
        this.inviteCodeGenerator = inviteCodeGenerator;
        this.houseCoverImageCatalog = houseCoverImageCatalog;
        this.bannedWordChecker = bannedWordChecker;
    }

    @Transactional
    public HouseCreateResponse create(Long userId, HouseCreateRequest request) {
        // 금칙어 차단 (#209)
        if (bannedWordChecker.containsBannedWord(request.name())) {
            throw new BusinessException(HouseErrorCode.HOUSE_NAME_BANNED);
        }
        houseCoverImageCatalog.validatePublished(request.coverImageKey());
        List<Long> goalIds = request.goalIds().stream().distinct().toList();
        List<Goal> goals = goalRepository.findByIdInAndActiveIsTrue(goalIds);
        if (goals.size() != goalIds.size()) {
            throw new BusinessException(HouseErrorCode.HOUSE_GOAL_INVALID);
        }

        User owner = userRepository.getReferenceById(userId);
        int maxMembers = request.maxMembers() == null ? DEFAULT_MAX_MEMBERS : request.maxMembers();
        House house = saveHouse(owner, request.name(), request.description(), request.coverImageKey(),
                maxMembers, goals);

        return new HouseCreateResponse(house.getId(), userId, house.getInviteCode(), house.getInviteExpiresAt());
    }

    // 온보딩 완료 시 선택 목표를 담은 기본 집을 지급함. 게시 manifest 첫 항목을 기본 커버로 사용함.
    @Transactional
    public void createOnboardingStarterHouse(User owner, List<Goal> selectedGoals) {
        List<Goal> starterGoals = selectedGoals.stream().distinct().limit(3).toList();
        if (starterGoals.isEmpty()) {
            throw new IllegalArgumentException("온보딩 기본 집에는 목표가 하나 이상 필요합니다.");
        }
        String coverImageKey = houseCoverImageCatalog.items().stream()
                .findFirst()
                .map(HouseCoverImageCatalog.PublishedCoverImage::coverImageKey)
                .orElse(null);
        saveHouse(owner, ONBOARDING_STARTER_HOUSE_NAME, null, coverImageKey,
                DEFAULT_MAX_MEMBERS, starterGoals);
    }

    private House saveHouse(User owner, String name, String description, String coverImageKey,
                            int maxMembers, List<Goal> goals) {
        House house = houseRepository.save(House.create(
                owner, name, description, coverImageKey, maxMembers,
                inviteCodeGenerator.generate(), Instant.now().plus(INVITE_CODE_TTL)));
        houseMemberRepository.save(HouseMember.create(house, owner, HouseMemberRole.OWNER));
        houseGoalRepository.saveAll(goals.stream().map(goal -> HouseGoal.create(house, goal)).toList());
        return house;
    }

    // 설정 수정 - 소유자 전용, null 필드는 변경하지 않는 부분 수정.
    @Transactional
    public HouseUpdateResponse updateSettings(Long userId, Long houseId, HouseUpdateRequest request) {
        House house = houseRepository.findById(houseId)
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new BusinessException(HouseErrorCode.HOUSE_NOT_FOUND));
        boolean isOwner = houseMemberRepository.findByHouseIdAndUserId(houseId, userId)
                .filter(HouseMember::isActive)
                .map(member -> member.getRole() == HouseMemberRole.OWNER)
                .orElse(false);
        if (!isOwner) {
            throw new BusinessException(HouseErrorCode.HOUSE_NOT_OWNER);
        }
        if (request.maxMembers() != null && request.maxMembers() < house.getCurrentMemberCount()) {
            throw new BusinessException(HouseErrorCode.HOUSE_MAX_MEMBERS_BELOW_CURRENT);
        }

        // 금칙어 차단 (#209) - 부분 수정이라 name 이 온 경우에만 검사
        if (request.name() != null && bannedWordChecker.containsBannedWord(request.name())) {
            throw new BusinessException(HouseErrorCode.HOUSE_NAME_BANNED);
        }
        houseCoverImageCatalog.validatePublished(request.coverImageKey());
        house.updateSettings(request.name(), request.description(), request.coverImageKey(), request.maxMembers());
        return new HouseUpdateResponse(house.getId(), house.getName(), house.getDescription(),
                house.getCoverImageKey(), house.getMaxMembers());
    }

    // 초대코드 재발급 - 활성 구성원 전용. 새 코드로 교체돼 기존 코드는 즉시 무효.
    // 소유자는 집 공용 코드(즉시가입)를, 일반 구성원은 본인 개인 코드(방장 승인 대기)를 재발급한다.
    // house 행 락으로 탈퇴·강퇴·참여와 직렬화한다 - 락 없이 읽고 갱신하면 dirty flush(전체 컬럼
    // UPDATE)가 동시에 커밋된 탈퇴·강퇴의 status/left_at 을 stale 값으로 되덮을 수 있다.
    @Transactional
    public InviteCodeResponse reissueInviteCode(Long userId, Long houseId) {
        House house = houseRepository.findWithLockById(houseId)
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new BusinessException(HouseErrorCode.HOUSE_NOT_FOUND));
        HouseMember member = houseMemberRepository.findByHouseIdAndUserId(houseId, userId)
                .filter(HouseMember::isActive)
                .orElseThrow(() -> new BusinessException(HouseErrorCode.HOUSE_NOT_MEMBER));

        Instant expiresAt = Instant.now().plus(INVITE_CODE_TTL);
        if (member.isOwner()) {
            house.updateInviteCode(inviteCodeGenerator.generate(), expiresAt);
            return new InviteCodeResponse(house.getInviteCode(), house.getInviteExpiresAt());
        }
        member.updateInviteCode(inviteCodeGenerator.generate(), expiresAt);
        return new InviteCodeResponse(member.getInviteCode(), member.getInviteExpiresAt());
    }
}
