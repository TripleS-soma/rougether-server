package com.triples.rougether.userapi.house.service;

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
import com.triples.rougether.userapi.bot.BotResidencyService;
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
    private final BotResidencyService botResidencyService;
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
                               BannedWordChecker bannedWordChecker,
                               BotResidencyService botResidencyService) {
        this.houseRepository = houseRepository;
        this.houseMemberRepository = houseMemberRepository;
        this.houseGoalRepository = houseGoalRepository;
        this.goalRepository = goalRepository;
        this.userRepository = userRepository;
        this.inviteCodeGenerator = inviteCodeGenerator;
        this.houseCoverImageCatalog = houseCoverImageCatalog;
        this.bannedWordChecker = bannedWordChecker;
        this.botResidencyService = botResidencyService;
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

    // 회원가입 트랜잭션에서 비공개 기본 집을 지급함(#322). 게시 manifest 첫 항목을 기본 커버로 쓰고 집 목표는 비워 둔다 —
    // 가입 시점엔 목표가 없으므로 PUT /onboarding/goals 가 fillStarterHouseGoals 로 채운다.
    // 호출자(SignupService)의 가입 트랜잭션에 합류해 유저·지갑·집이 함께 커밋/롤백된다.
    @Transactional
    public House createStarterHouse(User owner) {
        String coverImageKey = houseCoverImageCatalog.items().stream()
                .findFirst()
                .map(HouseCoverImageCatalog.PublishedCoverImage::coverImageKey)
                .orElse(null);
        House house = saveHouse(owner, ONBOARDING_STARTER_HOUSE_NAME, null, coverImageKey,
                DEFAULT_MAX_MEMBERS, List.of(), false);
        // 동거 봇(#309): 첫 사용자가 빈 집을 보지 않게 봇 2명을 함께 들인다. 사용자가 직접 만든 집엔 넣지 않는다.
        // 방금 저장한 house 는 이 트랜잭션이 소유하므로 별도 락 없이 카운트를 갱신해도 안전하다.
        botResidencyService.joinStarterHouse(house);
        return house;
    }

    // 온보딩 목표 저장 시 기본 집의 목표를 1회 채움(#322). 대상은 "내가 OWNER 이고 house_goals 가 비어 있는 집" —
    // 일반 집 생성 API 는 목표 1~3개가 필수라 비어 있는 집은 기본 집뿐이다. 이미 목표가 있으면 건드리지 않고(이후 목표
    // 변경은 반영하지 않음), 기본 집을 나가 소유권이 넘어갔으면 대상이 아니다. 최대 3개(호출자가 정렬해 넘김).
    @Transactional
    public void fillStarterHouseGoals(Long userId, List<Goal> goals) {
        List<Goal> starterGoals = goals.stream().distinct().limit(3).toList();
        if (starterGoals.isEmpty()) {
            return;
        }
        houseMemberRepository.findByUserIdAndStatusWithHouse(userId, HouseMemberStatus.ACTIVE).stream()
                .filter(HouseMember::isOwner)
                .map(HouseMember::getHouse)
                .filter(house -> !houseGoalRepository.existsByHouseId(house.getId()))
                .forEach(house -> houseGoalRepository.saveAll(
                        starterGoals.stream().map(goal -> HouseGoal.create(house, goal)).toList()));
    }

    private House saveHouse(User owner, String name, String description, String coverImageKey,
                            int maxMembers, List<Goal> goals) {
        return saveHouse(owner, name, description, coverImageKey, maxMembers, goals, true);
    }

    private House saveHouse(User owner, String name, String description, String coverImageKey,
                            int maxMembers, List<Goal> goals, boolean isPublic) {
        String inviteCode = inviteCodeGenerator.generate();
        Instant inviteExpiresAt = Instant.now().plus(INVITE_CODE_TTL);
        House newHouse = isPublic
                ? House.create(owner, name, description, coverImageKey, maxMembers, inviteCode, inviteExpiresAt)
                : House.createPrivate(owner, name, description, coverImageKey, maxMembers, inviteCode, inviteExpiresAt);
        House house = houseRepository.save(newHouse);
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
        house.updateSettings(request.name(), request.description(), request.coverImageKey(), request.maxMembers(),
                request.isPublic());
        return new HouseUpdateResponse(house.getId(), house.getName(), house.getDescription(),
                house.getCoverImageKey(), house.getMaxMembers(), house.isPublic());
    }

    // 닉네임 확정 시 기본 이름 그대로인 소유 집을 "{닉네임}의 집"으로 개명(#350) - MemberService 가
    // 닉네임 저장과 같은 트랜잭션에서 호출한다. 사용자가 직접 지은 이름은 건드리지 않고, 한 번 개명되면
    // 기본 이름이 아니게 되어 이후 닉네임 변경엔 따라가지 않는다. 금칙어는 닉네임 검사가 선행 커버.
    @Transactional
    public void renameDefaultNamedHouses(Long userId, String nickname) {
        houseMemberRepository.findByUserIdAndStatusWithHouse(userId, HouseMemberStatus.ACTIVE).stream()
                .filter(HouseMember::isOwner)
                .map(HouseMember::getHouse)
                .filter(house -> !house.isDeleted())
                .filter(house -> ONBOARDING_STARTER_HOUSE_NAME.equals(house.getName()))
                .forEach(house -> house.updateSettings(nickname + "의 집", null, null, null, null));
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
