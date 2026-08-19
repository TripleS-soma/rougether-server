package com.triples.rougether.userapi.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.goal.entity.Goal;
import com.triples.rougether.domain.goal.repository.GoalRepository;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.entity.HouseMemberStatus;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.policy.SignupWalletPolicy;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.room.entity.RoomGuestbook;
import com.triples.rougether.userapi.guestbook.dto.GuestbookListResponse.GuestbookItem;
import com.triples.rougether.userapi.house.dto.HouseCreateRequest;
import com.triples.rougether.userapi.house.dto.HouseMemberListResponse.MemberSummary;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import com.triples.rougether.userapi.house.service.HouseCommandService;
import com.triples.rougether.userapi.house.service.HouseJoinService;
import com.triples.rougether.userapi.house.service.HouseMemberCommandService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

// 동거 봇 거주 규칙(#309): 기본 집 자동 입주·자리 양보·해체·양도 금지·응답 필드.
// 회원탈퇴 승계·해체는 탈퇴 트랜잭션이 PC 를 비우므로(clearAutomatically) 비트랜잭션 클래스 BotResidencyWithdrawalTest 에서 본다.
@SpringBootTest
@Transactional
class BotResidencyTest {

    @Autowired UserRepository userRepository;
    @Autowired UserWalletRepository userWalletRepository;
    @Autowired GoalRepository goalRepository;
    @Autowired HouseRepository houseRepository;
    @Autowired HouseMemberRepository houseMemberRepository;
    @Autowired HouseCommandService houseCommandService;
    @Autowired HouseJoinService houseJoinService;
    @Autowired HouseMemberCommandService houseMemberCommandService;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void 온보딩_기본_집에는_부하가_낮은_봇_2명이_함께_들어오고_사용자가_만든_집엔_안_들어온다() {
        User busy = bot("residency-busy");
        User idle1 = bot("residency-idle-1");
        User idle2 = bot("residency-idle-2");
        // busy 봇은 이미 다른 집 2곳에 소속 → 부하가 커서 선택되지 않아야 한다.
        for (int i = 0; i < 2; i++) {
            House other = house(human("residency-other-owner-" + i), "다른 집 " + i, "OTHERH0" + i, 4);
            houseMemberRepository.save(HouseMember.create(other, busy, HouseMemberRole.MEMBER));
        }
        User newcomer = human("residency-newcomer");
        Goal goal = goal("residency_goal");

        houseCommandService.createStarterHouse(newcomer);

        House starter = houseRepository.findAll().stream()
                .filter(h -> h.getOwner().getId().equals(newcomer.getId()))
                .findFirst().orElseThrow();
        List<HouseMember> members = houseMemberRepository
                .findByHouseIdAndStatusWithUser(starter.getId(), HouseMemberStatus.ACTIVE);
        assertThat(members).hasSize(3);
        assertThat(members).extracting(m -> m.getUser().getId())
                .contains(newcomer.getId(), idle1.getId(), idle2.getId())
                .doesNotContain(busy.getId());
        assertThat(starter.getCurrentMemberCount()).isEqualTo(3);
        assertThat(members).filteredOn(m -> m.getUser().isBot())
                .allSatisfy(m -> assertThat(m.getRole()).isEqualTo(HouseMemberRole.MEMBER));

        // 사용자가 직접 만든 집: 봇 없음
        Long created = houseCommandService.create(newcomer.getId(),
                new HouseCreateRequest("내가 만든 집", null, null, 4, List.of(goal.getId()))).houseId();
        assertThat(houseMemberRepository.findByHouseIdAndStatusWithUser(created, HouseMemberStatus.ACTIVE))
                .hasSize(1)
                .allSatisfy(m -> assertThat(m.getUser().isBot()).isFalse());
    }

    @Test
    void 봇_풀이_비어_있으면_기본_집은_사람_1명으로_만들어진다() {
        User newcomer = human("residency-no-bots");
        houseCommandService.createStarterHouse(newcomer);

        House starter = houseRepository.findAll().stream()
                .filter(h -> h.getOwner().getId().equals(newcomer.getId()))
                .findFirst().orElseThrow();
        assertThat(starter.getCurrentMemberCount()).isEqualTo(1);
    }

    @Test
    void 만석_집에_사람이_들어오면_가장_나중에_들어온_봇이_자리를_비운다() {
        User owner = human("residency-yield-owner");
        User bot1 = bot("residency-yield-bot-1");
        User bot2 = bot("residency-yield-bot-2");
        House house = house(owner, "양보 집", "YIELD001", 4);
        // 가입 시각을 명시적으로 벌려 "가장 나중에 들어온 봇" 판정을 결정적으로 만든다(bot1 이 2시간 먼저).
        HouseMember b1 = HouseMember.create(house, bot1, HouseMemberRole.MEMBER);
        ReflectionTestUtils.setField(b1, "joinedAt", Instant.now().minus(Duration.ofHours(2)));
        houseMemberRepository.save(b1);
        houseMemberRepository.save(HouseMember.create(house, bot2, HouseMemberRole.MEMBER));
        house.increaseMemberCount();
        house.increaseMemberCount();
        User human2 = human("residency-yield-h2");
        houseJoinService.joinByCode(human2.getId(), house.getInviteCode()); // 4/4 (사람 2 + 봇 2)
        assertThat(house.isFull()).isTrue();

        User human3 = human("residency-yield-h3");
        houseJoinService.joinByCode(human3.getId(), house.getInviteCode());

        assertThat(house.getCurrentMemberCount()).isEqualTo(4);
        assertThat(activeUserIds(house)).contains(owner.getId(), human2.getId(), human3.getId(), bot1.getId())
                .doesNotContain(bot2.getId()); // 나중에 들어온 bot2 가 비켜준다
        assertThat(houseMemberRepository.findByHouseIdAndUserId(house.getId(), bot2.getId()).orElseThrow().getStatus())
                .isEqualTo(HouseMemberStatus.LEFT);

        User human4 = human("residency-yield-h4");
        houseJoinService.joinByCode(human4.getId(), house.getInviteCode());
        assertThat(activeUserIds(house)).doesNotContain(bot1.getId(), bot2.getId()).hasSize(4);
        assertThat(house.getCurrentMemberCount()).isEqualTo(4);

        // 봇이 더 없으면 만석 그대로
        User human5 = human("residency-yield-h5");
        assertThatThrownBy(() -> houseJoinService.joinByCode(human5.getId(), house.getInviteCode()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(HouseErrorCode.HOUSE_FULL);
    }

    @Test
    void 마지막_사람이_나가면_봇을_내보내고_집을_해체하며_봇에게는_양도할_수_없다() {
        User owner = human("residency-leave-owner");
        User bot = bot("residency-leave-bot");
        House house = house(owner, "해체 집", "DISSOL01", 4);
        HouseMember botMember = houseMemberRepository.save(HouseMember.create(house, bot, HouseMemberRole.MEMBER));
        house.increaseMemberCount();

        assertThatThrownBy(() -> houseMemberCommandService.transferOwnership(owner.getId(), house.getId(), botMember.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(HouseErrorCode.HOUSE_OWNER_TRANSFER_TO_BOT);

        // 사람이 하나 더 있으면 소유자는 여전히 양도 후 탈퇴
        User human2 = human("residency-leave-h2");
        houseJoinService.joinByCode(human2.getId(), house.getInviteCode());
        assertThatThrownBy(() -> houseMemberCommandService.leave(owner.getId(), house.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(HouseErrorCode.HOUSE_OWNER_MUST_TRANSFER);
        houseMemberCommandService.leave(human2.getId(), house.getId());
        assertThat(house.isDeleted()).isFalse(); // 사람(owner)이 남아 있으면 유지

        // 마지막 사람(소유자)이 나가면 봇 LEFT + soft delete
        houseMemberCommandService.leave(owner.getId(), house.getId());
        assertThat(house.isDeleted()).isTrue();
        assertThat(house.getCurrentMemberCount()).isZero();
        assertThat(houseMemberRepository.findByHouseIdAndUserId(house.getId(), bot.getId()).orElseThrow().getStatus())
                .isEqualTo(HouseMemberStatus.LEFT);
    }

    @Test
    void 봇이_채운_만석_집은_입주_신청을_받고_방장이_수락할_때_봇이_자리를_비운다() {
        User owner = human("residency-req-owner");
        User bot = bot("residency-req-bot");
        House house = house(owner, "신청 집", "REQFULL1", 2);
        houseMemberRepository.save(HouseMember.create(house, bot, HouseMemberRole.MEMBER));
        house.increaseMemberCount(); // 2/2 (사람 1 + 봇 1)
        assertThat(house.isFull()).isTrue();

        User applicant = human("residency-req-applicant");
        Long requestId = houseJoinService.requestJoin(applicant.getId(), house.getId()).requestId();
        assertThat(activeUserIds(house)).contains(bot.getId()); // 신청 단계에서는 봇이 그대로

        houseJoinService.acceptRequest(owner.getId(), house.getId(), requestId);

        assertThat(house.getCurrentMemberCount()).isEqualTo(2);
        assertThat(activeUserIds(house)).containsExactlyInAnyOrder(owner.getId(), applicant.getId());
        assertThat(houseMemberRepository.findByHouseIdAndUserId(house.getId(), bot.getId()).orElseThrow().getStatus())
                .isEqualTo(HouseMemberStatus.LEFT);

        // 사람만으로 만석이면 신청 자체가 HOUSE_FULL
        User late = human("residency-req-late");
        assertThatThrownBy(() -> houseJoinService.requestJoin(late.getId(), house.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(HouseErrorCode.HOUSE_FULL);
    }

    @Test
    void 소유자는_봇을_강퇴할_수_있고_봇만_남지_않는_한_집은_유지된다() {
        User owner = human("residency-kick-owner");
        User bot = bot("residency-kick-bot");
        House house = house(owner, "강퇴 집", "KICKBOT1", 4);
        HouseMember botMember = houseMemberRepository.save(HouseMember.create(house, bot, HouseMemberRole.MEMBER));
        house.increaseMemberCount();

        houseMemberCommandService.kick(owner.getId(), house.getId(), botMember.getId());

        assertThat(house.getCurrentMemberCount()).isEqualTo(1);
        assertThat(house.isDeleted()).isFalse();
        assertThat(houseMemberRepository.findById(botMember.getId()).orElseThrow().getStatus())
                .isEqualTo(HouseMemberStatus.KICKED);
    }

    @Test
    void 구성원_목록과_방명록_응답에_봇_여부가_실린다() {
        User owner = human("residency-dto-owner");
        User bot = bot("residency-dto-bot");
        House house = house(owner, "응답 집", "DTOHOUS1", 4);
        HouseMember botMember = houseMemberRepository.save(HouseMember.create(house, bot, HouseMemberRole.MEMBER));
        HouseMember ownerMember = houseMemberRepository.findByHouseIdAndUserId(house.getId(), owner.getId()).orElseThrow();

        assertThat(MemberSummary.of(botMember).bot()).isTrue();
        assertThat(MemberSummary.of(ownerMember).bot()).isFalse();
        assertThat(GuestbookItem.of(RoomGuestbook.write(owner, house, bot, "봇이 남긴 방명록")).authorBot()).isTrue();
        assertThat(GuestbookItem.of(RoomGuestbook.write(bot, house, owner, "사람이 남긴 방명록")).authorBot()).isFalse();
    }

    private List<Long> activeUserIds(House house) {
        return houseMemberRepository.findByHouseIdAndStatusWithUser(house.getId(), HouseMemberStatus.ACTIVE)
                .stream().map(m -> m.getUser().getId()).toList();
    }

    private User human(String key) {
        User user = userRepository.save(User.signUp(key + "@rougether.dev"));
        userWalletRepository.saveAll(SignupWalletPolicy.issueAll(user));
        return user;
    }

    private User bot(String key) {
        User user = userRepository.save(User.bot(key, "봇 " + key, null));
        userWalletRepository.saveAll(SignupWalletPolicy.issueAll(user));
        return user;
    }

    private House house(User owner, String name, String inviteCode, int maxMembers) {
        House house = houseRepository.save(House.create(
                owner, name, null, null, maxMembers, inviteCode, Instant.now().plus(Duration.ofDays(7))));
        houseMemberRepository.save(HouseMember.create(house, owner, HouseMemberRole.OWNER));
        return house;
    }

    private Goal goal(String code) {
        jdbcTemplate.update("INSERT INTO goals (code, name, sort_order, is_active) VALUES (?, ?, 996, true)", code, code);
        Long id = jdbcTemplate.queryForObject("SELECT id FROM goals WHERE code = ? ORDER BY id DESC LIMIT 1", Long.class, code);
        return goalRepository.findById(id).orElseThrow();
    }
}
