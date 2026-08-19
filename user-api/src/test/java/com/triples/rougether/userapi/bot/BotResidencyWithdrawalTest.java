package com.triples.rougether.userapi.bot;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.triples.rougether.userapi.house.service.HouseJoinService;
import com.triples.rougether.userapi.member.service.MemberWithdrawalService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

// 회원탈퇴 경로의 동거 봇 규칙(#309): 봇은 승계 후보가 아니고, 사람이 0명이 되면 봇을 내보내고 집을 해체한다.
// 탈퇴 트랜잭션은 내부 bulk delete 로 영속성 컨텍스트를 비우므로 @Transactional 테스트로는 검증할 수 없어
// 실제 커밋 후 DB 를 다시 읽고, 만든 봇·집은 직접 지운다(봇 잔존 시 BotSeederTest 의 봇 수 단언이 깨짐).
@SpringBootTest
class BotResidencyWithdrawalTest {

    @Autowired MemberWithdrawalService memberWithdrawalService;
    @Autowired HouseJoinService houseJoinService;
    @Autowired UserRepository userRepository;
    @Autowired UserWalletRepository userWalletRepository;
    @Autowired HouseRepository houseRepository;
    @Autowired HouseMemberRepository houseMemberRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private final List<Long> houseIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Long houseId : houseIds) {
            jdbcTemplate.update("DELETE FROM house_goals WHERE house_id = ?", houseId);
            jdbcTemplate.update("DELETE FROM house_members WHERE house_id = ?", houseId);
            jdbcTemplate.update("DELETE FROM house WHERE id = ?", houseId);
        }
        for (Long userId : userIds) {
            jdbcTemplate.update("DELETE FROM notification WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM wallet_histories WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM user_wallets WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM refresh_tokens WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
    }

    @Test
    void 소유자_탈퇴_시_먼저_들어온_봇이_아니라_사람이_승계하고_마지막_사람이_탈퇴하면_봇을_내보내고_해체한다() {
        User owner = human("wd-owner");
        User bot = bot("wd-bot");
        House house = house(owner);
        HouseMember botMember = HouseMember.create(house, bot, HouseMemberRole.MEMBER);
        ReflectionTestUtils.setField(botMember, "joinedAt", Instant.now().minus(Duration.ofDays(1)));
        houseMemberRepository.save(botMember);
        jdbcTemplate.update("UPDATE house SET current_member_count = 2 WHERE id = ?", house.getId());
        User human2 = human("wd-h2");
        houseJoinService.joinByCode(human2.getId(), house.getInviteCode()); // 사람 2 + 봇 1

        memberWithdrawalService.withdraw(owner.getId());

        HouseMember successor = houseMemberRepository.findByHouseIdAndUserId(house.getId(), human2.getId()).orElseThrow();
        assertThat(successor.getRole()).isEqualTo(HouseMemberRole.OWNER);
        assertThat(successor.getStatus()).isEqualTo(HouseMemberStatus.ACTIVE);
        HouseMember botAfter = houseMemberRepository.findByHouseIdAndUserId(house.getId(), bot.getId()).orElseThrow();
        assertThat(botAfter.getRole()).isEqualTo(HouseMemberRole.MEMBER);
        assertThat(botAfter.getStatus()).isEqualTo(HouseMemberStatus.ACTIVE);
        House afterFirst = houseRepository.findById(house.getId()).orElseThrow();
        assertThat(afterFirst.getOwner().getId()).isEqualTo(human2.getId());
        assertThat(afterFirst.isDeleted()).isFalse();
        assertThat(afterFirst.getCurrentMemberCount()).isEqualTo(2);

        memberWithdrawalService.withdraw(human2.getId());

        House afterSecond = houseRepository.findById(house.getId()).orElseThrow();
        assertThat(afterSecond.isDeleted()).isTrue();
        assertThat(afterSecond.getCurrentMemberCount()).isZero();
        assertThat(houseMemberRepository.findByHouseIdAndUserId(house.getId(), bot.getId()).orElseThrow().getStatus())
                .isEqualTo(HouseMemberStatus.LEFT);
    }

    private User human(String prefix) {
        User user = userRepository.save(User.signUp(prefix + "-" + UUID.randomUUID() + "@rougether.dev"));
        userWalletRepository.saveAll(SignupWalletPolicy.issueAll(user));
        userIds.add(user.getId());
        return user;
    }

    private User bot(String prefix) {
        User user = userRepository.save(User.bot(prefix + "-" + UUID.randomUUID().toString().substring(0, 12), "탈퇴 테스트 봇", null));
        userWalletRepository.saveAll(SignupWalletPolicy.issueAll(user));
        userIds.add(user.getId());
        return user;
    }

    private House house(User owner) {
        String code = ("BW" + UUID.randomUUID().toString().replace("-", "")).substring(0, 8).toUpperCase();
        House house = houseRepository.save(House.create(
                owner, "봇 탈퇴 집", null, null, 4, code, Instant.now().plus(Duration.ofDays(7))));
        houseMemberRepository.save(HouseMember.create(house, owner, HouseMemberRole.OWNER));
        houseIds.add(house.getId());
        return house;
    }
}
