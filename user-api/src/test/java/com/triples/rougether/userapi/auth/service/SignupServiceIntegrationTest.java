package com.triples.rougether.userapi.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.entity.HouseMemberStatus;
import com.triples.rougether.domain.house.repository.HouseGoalRepository;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.entity.WalletHistory;
import com.triples.rougether.domain.member.policy.SignupWalletPolicy;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.member.repository.WalletHistoryRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.shared.WalletHistoryReason;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

// 회원가입 공통 지급(#322): 유저·지갑·원장·기본 집이 한 트랜잭션으로 생기고, 집 생성이 실패하면 가입 전체가 롤백된다
@SpringBootTest
class SignupServiceIntegrationTest {

    private static final String DEFAULT_COVER_KEY =
            "house/cloud-balloon/house-unified-cloud-balloon-frame.png";

    @Autowired private SignupService signupService;
    @Autowired private UserRepository userRepository;
    @Autowired private UserWalletRepository userWalletRepository;
    @Autowired private WalletHistoryRepository walletHistoryRepository;
    @Autowired private HouseMemberRepository houseMemberRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoSpyBean private HouseGoalRepository houseGoalRepository;

    private Long userId;

    @AfterEach
    void cleanUp() {
        reset(houseGoalRepository);
        if (userId != null) {
            jdbcTemplate.update("DELETE FROM house_goals WHERE house_id IN (SELECT id FROM house WHERE owner_user_id = ?)", userId);
            // 봇이 켜진 컨텍스트에서도 깨지지 않게 집 기준으로 멤버십 전부 제거
            jdbcTemplate.update("DELETE FROM house_members WHERE house_id IN (SELECT id FROM house WHERE owner_user_id = ?)", userId);
            jdbcTemplate.update("DELETE FROM house_members WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM house WHERE owner_user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM wallet_histories WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM user_wallets WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
    }

    @Test
    void 가입하면_유저_지갑_원장_기본_집이_함께_생긴다() {
        User user = signupService.register("signup@test.com");
        userId = user.getId();

        assertThat(userRepository.findById(userId)).isPresent()
                .get().extracting(User::getEmail).isEqualTo("signup@test.com");
        assertThat(userWalletRepository.findByUserId(userId))
                .extracting(UserWallet::getCurrencyType, UserWallet::getBalance)
                .containsExactlyInAnyOrder(
                        tuple(CurrencyType.COIN, SignupWalletPolicy.INITIAL_COIN_BALANCE),
                        tuple(CurrencyType.DIAMOND, 0));
        assertThat(walletHistoryRepository.findAll().stream()
                .filter(h -> h.getUser().getId().equals(userId))
                .map(WalletHistory::getReason))
                .contains(WalletHistoryReason.SIGNUP_BONUS);

        // 기본 집: 나의 집, 비공개, 정원 4, 게시 커버 첫 항목, OWNER 멤버십, 목표는 비어 있음(온보딩 목표 저장이 채움)
        List<HouseMember> memberships = houseMemberRepository
                .findByUserIdAndStatusWithHouse(userId, HouseMemberStatus.ACTIVE);
        assertThat(memberships).hasSize(1);
        HouseMember membership = memberships.getFirst();
        House house = membership.getHouse();
        assertThat(membership.getRole()).isEqualTo(HouseMemberRole.OWNER);
        assertThat(house.getOwner().getId()).isEqualTo(userId);
        assertThat(house.getName()).isEqualTo("나의 집");
        assertThat(house.isPublic()).isFalse();
        assertThat(house.getCoverImageKey()).isEqualTo(DEFAULT_COVER_KEY);
        assertThat(house.getMaxMembers()).isEqualTo(4);
        // 테스트 컨텍스트는 봇 비활성(봇 풀 없음) → 사람 1명
        assertThat(house.getCurrentMemberCount()).isEqualTo(1);
        assertThat(houseGoalRepository.findByHouseId(house.getId())).isEmpty();
    }

    @Test
    void email이_없는_가입도_기본_집을_받는다() {
        User user = signupService.register(null);
        userId = user.getId();

        assertThat(user.getEmail()).isNull();
        assertThat(houseMemberRepository.findByUserIdAndStatusWithHouse(userId, HouseMemberStatus.ACTIVE))
                .hasSize(1);
    }

    @Test
    void 기본_집_생성이_실패하면_유저와_지갑도_만들어지지_않는다() {
        long usersBefore = userRepository.count();
        long walletsBefore = userWalletRepository.count();
        doThrow(new RuntimeException("기본 집 저장 실패")).when(houseGoalRepository).saveAll(anyList());

        assertThatThrownBy(() -> signupService.register("rollback@test.com"))
                .isInstanceOf(RuntimeException.class);

        assertThat(userRepository.count()).isEqualTo(usersBefore);
        assertThat(userWalletRepository.count()).isEqualTo(walletsBefore);
        assertThat(userRepository.findAll()).noneMatch(u -> "rollback@test.com".equals(u.getEmail()));
    }
}
