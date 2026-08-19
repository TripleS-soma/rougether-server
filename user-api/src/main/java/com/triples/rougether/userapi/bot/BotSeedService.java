package com.triples.rougether.userapi.bot;

import com.triples.rougether.domain.character.entity.Character;
import com.triples.rougether.domain.character.entity.UserCharacter;
import com.triples.rougether.domain.character.repository.CharacterRepository;
import com.triples.rougether.domain.character.repository.UserCharacterRepository;
import com.triples.rougether.domain.goal.entity.Goal;
import com.triples.rougether.domain.goal.entity.UserGoal;
import com.triples.rougether.domain.goal.repository.GoalRepository;
import com.triples.rougether.domain.goal.repository.UserGoalRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.policy.SignupWalletPolicy;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Category;
import com.triples.rougether.domain.routine.entity.PrivacyScope;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.repository.CategoryRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.UserItem;
import com.triples.rougether.domain.shop.repository.ItemRepository;
import com.triples.rougether.domain.shop.repository.UserItemRepository;
import com.triples.rougether.userapi.global.text.BannedWordChecker;
import com.triples.rougether.userapi.room.dto.RoomLayoutUpdateRequest;
import com.triples.rougether.userapi.room.service.RoomCommandService;
import com.triples.rougether.userapi.wallet.service.WalletHistoryRecorder;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 동거 봇 1명 시드(#307) — 봇 1명 = 트랜잭션 1개. 루프는 BotSeedRunner 가 돌리고 이 클래스는 프록시를 통해
// 한 봇씩 호출된다(같은 빈 안에서 자기호출하면 @Transactional 이 적용되지 않으므로 루프를 이 클래스에 두지 않는다).
// botKey 로 이미 있으면 닉네임·bio 변경분만 갱신하고, 없으면 유저·지갑·목표·대표 캐릭터·카테고리·루틴 4개·
// 가구 지급·FREE_V1 프리셋 배치까지 한 번에 만든다. 활성 목표·캐릭터가 없거나 게시 가구가 6개 미만이면 그 봇은
// 만들지 않고 건너뛴다(반쪽 봇을 남기지 않기 위해 — 다음 기동에서 온전히 만들어진다).
@Slf4j
@Service
public class BotSeedService {

    static final int FURNITURE_PER_BOT = 8;
    static final int FURNITURE_MIN = 6;
    static final String POSITIONED_PLACEMENT_TYPE = "positioned";
    private static final String ROUTINE_REPEAT_DAILY = "DAILY";

    private final UserRepository userRepository;
    private final UserWalletRepository userWalletRepository;
    private final WalletHistoryRecorder walletHistoryRecorder;
    private final GoalRepository goalRepository;
    private final UserGoalRepository userGoalRepository;
    private final CharacterRepository characterRepository;
    private final UserCharacterRepository userCharacterRepository;
    private final CategoryRepository categoryRepository;
    private final RoutineRepository routineRepository;
    private final ItemRepository itemRepository;
    private final UserItemRepository userItemRepository;
    private final RoomCommandService roomCommandService;
    private final BannedWordChecker bannedWordChecker;
    private final Clock clock;

    public BotSeedService(UserRepository userRepository,
                          UserWalletRepository userWalletRepository,
                          WalletHistoryRecorder walletHistoryRecorder,
                          GoalRepository goalRepository,
                          UserGoalRepository userGoalRepository,
                          CharacterRepository characterRepository,
                          UserCharacterRepository userCharacterRepository,
                          CategoryRepository categoryRepository,
                          RoutineRepository routineRepository,
                          ItemRepository itemRepository,
                          UserItemRepository userItemRepository,
                          RoomCommandService roomCommandService,
                          BannedWordChecker bannedWordChecker,
                          Clock clock) {
        this.userRepository = userRepository;
        this.userWalletRepository = userWalletRepository;
        this.walletHistoryRecorder = walletHistoryRecorder;
        this.goalRepository = goalRepository;
        this.userGoalRepository = userGoalRepository;
        this.characterRepository = characterRepository;
        this.userCharacterRepository = userCharacterRepository;
        this.categoryRepository = categoryRepository;
        this.routineRepository = routineRepository;
        this.itemRepository = itemRepository;
        this.userItemRepository = userItemRepository;
        this.roomCommandService = roomCommandService;
        this.bannedWordChecker = bannedWordChecker;
        this.clock = clock;
    }

    public enum Outcome { CREATED, UPDATED, SKIPPED }

    @Transactional(readOnly = true)
    public boolean exists(String botKey) {
        return userRepository.findByBotKey(botKey).isPresent();
    }

    // 봇 1명을 카탈로그와 동기화한다. 반드시 프록시를 통해(다른 빈에서) 호출할 것.
    @Transactional
    public Outcome seed(BotProfile profile, int index) {
        warnIfBanned(profile);
        Optional<User> existing = userRepository.findByBotKey(profile.botKey());
        if (existing.isPresent()) {
            User bot = existing.get();
            if (bot.isDeleted()) {
                // 봇은 탈퇴 경로가 없어 원칙적으로 오지 않는 상태. 되살리지 않고 운영이 판단하도록 경고만 남긴다.
                log.warn("동거 봇이 soft delete 상태라 시드를 건너뜀 - botKey={}, userId={}", profile.botKey(), bot.getId());
                return Outcome.SKIPPED;
            }
            syncProfile(bot, profile);
            return Outcome.UPDATED;
        }

        List<Goal> activeGoals = goalRepository.findByActiveTrueOrderBySortOrderAsc();
        List<Character> activeCharacters = characterRepository.findByActiveTrueOrderBySortOrderAsc();
        List<Item> furnitureCandidates = furnitureCandidates();
        if (activeGoals.isEmpty() || activeCharacters.isEmpty() || furnitureCandidates.size() < FURNITURE_MIN) {
            log.warn("동거 봇 생성 건너뜀 - 마스터 데이터 미비(목표 {}, 캐릭터 {}, 가구 {}/{}), botKey={}",
                    activeGoals.size(), activeCharacters.size(), furnitureCandidates.size(), FURNITURE_MIN,
                    profile.botKey());
            return Outcome.SKIPPED;
        }

        User bot = userRepository.save(User.bot(profile.botKey(), profile.nickname(), profile.bio()));
        // 가입 시 통화별 지갑 발급 + 가입 보너스 원장 기록 — 일반 회원(AuthService.devLogin)과 같은 경로.
        walletHistoryRecorder.recordSignupBonus(
                userWalletRepository.saveAll(SignupWalletPolicy.issueAll(bot)));

        assignGoals(bot, profile, activeGoals);
        assignCharacter(bot, profile, index, activeCharacters);
        Category category = categoryRepository.save(Category.create(
                bot, profile.categoryName(), profile.categoryColorHex(), null, 0, PrivacyScope.HOUSE));
        createRoutines(bot, category, profile);
        List<Long> userItemIds = grantFurniture(bot, index, furnitureCandidates);
        placeRoom(bot, userItemIds);
        log.info("동거 봇 생성 - botKey={}, userId={}, furniture={}", profile.botKey(), bot.getId(), userItemIds.size());
        return Outcome.CREATED;
    }

    private void warnIfBanned(BotProfile profile) {
        // 회원 닉네임과 같은 금칙어 규칙을 적용하되 시드를 막지는 않는다 — 운영자가 로그로 인지해 카탈로그를 고친다.
        if (bannedWordChecker.containsBannedWord(profile.nickname())
                || bannedWordChecker.containsBannedWord(profile.bio())) {
            log.warn("동거 봇 프로필이 금칙어에 걸림 - botKey={}, 카탈로그 문구 수정 필요", profile.botKey());
        }
    }

    private void syncProfile(User bot, BotProfile profile) {
        if (!Objects.equals(bot.getNickname(), profile.nickname())) {
            bot.changeNickname(profile.nickname());
        }
        if (!Objects.equals(bot.getBio(), profile.bio())) {
            bot.changeBio(profile.bio());
        }
    }

    // 선호 코드 순으로 활성 목표를 고르고(최대 2개), 하나도 매칭되지 않으면 첫 활성 목표를 준다.
    private void assignGoals(User bot, BotProfile profile, List<Goal> activeGoals) {
        List<Goal> chosen = new ArrayList<>();
        for (String code : profile.goalCodes()) {
            activeGoals.stream()
                    .filter(goal -> goal.getCode().equals(code))
                    .findFirst()
                    .filter(goal -> !chosen.contains(goal))
                    .ifPresent(chosen::add);
        }
        if (chosen.isEmpty()) {
            chosen.add(activeGoals.get(0));
        }
        for (int i = 0; i < chosen.size(); i++) {
            userGoalRepository.save(UserGoal.of(bot, chosen.get(i), i == 0));
        }
    }

    // 프로필의 대표 캐릭터 코드가 활성 캐릭터에 있으면 그것을, 없으면 활성 캐릭터를 sortOrder 순으로 봇 인덱스만큼
    // 돌려가며 대표 캐릭터로 지급한다.
    private void assignCharacter(User bot, BotProfile profile, int index, List<Character> activeCharacters) {
        Character chosen = activeCharacters.stream()
                .filter(character -> character.getCode().equals(profile.characterCode()))
                .findFirst()
                .orElseGet(() -> {
                    log.warn("동거 봇 대표 캐릭터 코드 미존재 - code={}, 순번 배정으로 대체, botKey={}",
                            profile.characterCode(), profile.botKey());
                    return activeCharacters.get(index % activeCharacters.size());
                });
        userCharacterRepository.save(UserCharacter.createSelected(bot, chosen));
    }

    private void createRoutines(User bot, Category category, BotProfile profile) {
        LocalDate today = LocalDate.now(clock);
        for (String title : profile.routineTitles()) {
            Routine routine = routineRepository.save(Routine.create(
                    bot, category, title, AuthType.CHECK, ROUTINE_REPEAT_DAILY, null,
                    null, today, null));
            routine.assignOriginToSelf();
        }
    }

    // 활성 테마의 활성 positioned(가구·소품) 아이템. findAllWithTheme 의 theme.id·id 정렬을 그대로 써서
    // 봇 인덱스 오프셋 배분이 기동마다 같은 결과가 되게 한다.
    private List<Item> furnitureCandidates() {
        return itemRepository.findAllWithTheme().stream()
                .filter(Item::isActive)
                .filter(item -> item.getTheme().isActive())
                .filter(item -> POSITIONED_PLACEMENT_TYPE.equals(item.getPlacementType()))
                .toList();
    }

    // 후보(≥6개 보장)를 봇 인덱스만큼 오프셋해 최대 8개 지급한다.
    private List<Long> grantFurniture(User bot, int index, List<Item> candidates) {
        int count = Math.min(FURNITURE_PER_BOT, candidates.size());
        int offset = (index * FURNITURE_PER_BOT) % candidates.size();
        List<UserItem> granted = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            granted.add(UserItem.create(bot, candidates.get((offset + i) % candidates.size())));
        }
        return userItemRepository.saveAll(granted).stream().map(UserItem::getId).toList();
    }

    // 지급된 가구를 프리셋 1번 좌표로 자유배치 저장(FREE_V1 전환·revision 증가 포함).
    private void placeRoom(User bot, List<Long> userItemIds) {
        roomCommandService.updateLayout(bot.getId(), new RoomLayoutUpdateRequest(
                0, List.of(), BotRoomPreset.first().placementsFor(userItemIds)));
    }
}
