package com.triples.rougether.userapi.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.entity.WalletHistory;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.member.repository.WalletHistoryRepository;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.routine.repository.StreakRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.shared.WalletHistoryReason;
import com.triples.rougether.userapi.auth.dto.LoginResponse;
import com.triples.rougether.userapi.auth.service.AuthService;
import com.triples.rougether.userapi.invite.service.InviteService;
import com.triples.rougether.userapi.routine.dto.RoutineLogCreateRequest;
import com.triples.rougether.userapi.routine.service.RoutineLogService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;

// 재화 원장 트랜잭션 정합성(#253) — 위험영역(재화).
// 지갑 증감이 롤백되면 원장 기록도 함께 롤백되는지, 가입 보너스·초대 보상 경로도 원장에 남는지 검증함.
@SpringBootTest
class WalletHistoryRollbackTest {

    @Autowired
    private RoutineLogService routineLogService;
    @Autowired
    private AuthService authService;
    @Autowired
    private InviteService inviteService;
    @Autowired
    private RoutineRepository routineRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserWalletRepository userWalletRepository;
    @Autowired
    private WalletHistoryRepository walletHistoryRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private StreakRepository streakRepository;

    private final List<Long> userIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        // 테스트 트랜잭션이 없어 직접 정리함. FK 역순으로 지움
        for (Long id : userIds) {
            jdbcTemplate.update("delete from wallet_histories where user_id = ?", id);
            jdbcTemplate.update("delete from refresh_tokens where user_id = ?", id);
            jdbcTemplate.update("delete from invite_rewards where inviter_user_id = ? or invitee_user_id = ?",
                    id, id);
            jdbcTemplate.update("delete from user_invite_codes where user_id = ?", id);
            jdbcTemplate.update("delete from routine_logs where routine_id in "
                    + "(select id from routines where user_id = ?)", id);
            jdbcTemplate.update("delete from streaks where user_id = ?", id);
            jdbcTemplate.update("delete from routines where user_id = ?", id);
            // dev-login 가입은 기본 집(나의 집)·소유자 멤버십도 만든다(#322) — users 삭제 전에 치운다
            jdbcTemplate.update("delete from house_goals where house_id in (select id from house where owner_user_id = ?)", id);
            jdbcTemplate.update("delete from house_members where house_id in (select id from house where owner_user_id = ?)", id);
            jdbcTemplate.update("delete from house_members where user_id = ?", id);
            jdbcTemplate.update("delete from house where owner_user_id = ?", id);
            jdbcTemplate.update("delete from user_wallets where user_id = ?", id);
            jdbcTemplate.update("delete from users where id = ?", id);
        }
        userIds.clear();
    }

    @Test
    void 완료_중_스트릭_단계가_실패하면_원장_기록도_함께_롤백된다() {
        User user = userRepository.save(User.signUp());
        userIds.add(user.getId());
        Long routineId = routineRepository.save(Routine.create(user, null, "아침 운동",
                AuthType.CHECK, null, null, null, null, null)).getId();
        persistWallet(user, 0);

        doThrow(new RuntimeException("스트릭 저장 실패")).when(streakRepository).save(any());

        assertThatThrownBy(() -> routineLogService.complete(user.getId(), routineId,
                new RoutineLogCreateRequest(null)))
                .isInstanceOf(RuntimeException.class);

        // 지갑 적립과 같은 트랜잭션이므로 원장 row 도 남으면 안 됨
        assertThat(historiesOf(user.getId())).isEmpty();
    }

    @Test
    void 가입하면_SIGNUP_BONUS_이력이_초기_잔액_스냅샷과_함께_남는다() {
        LoginResponse response = authService.devLogin(null);
        userIds.add(response.userId());

        List<WalletHistory> histories = historiesOf(response.userId());
        assertThat(histories).hasSize(1);
        assertThat(histories.get(0).getReason()).isEqualTo(WalletHistoryReason.SIGNUP_BONUS);
        assertThat(histories.get(0).getCurrencyType()).isEqualTo(CurrencyType.COIN);
        assertThat(histories.get(0).getAmount()).isEqualTo(100);
        assertThat(histories.get(0).getBalanceAfter()).isEqualTo(100);
    }

    @Test
    void 초대코드를_사용하면_초대자와_피초대자_양쪽에_INVITE_REWARD_이력이_남는다() {
        User inviter = userRepository.save(User.signUp());
        User invitee = userRepository.save(User.signUp());
        userIds.add(inviter.getId());
        userIds.add(invitee.getId());
        persistWallet(inviter, 0);
        persistWallet(invitee, 0);
        String code = inviteService.getMyCode(inviter.getId()).code();

        inviteService.redeem(invitee.getId(), code);

        List<WalletHistory> inviterHistories = historiesOf(inviter.getId());
        assertThat(inviterHistories).hasSize(1);
        assertThat(inviterHistories.get(0).getReason()).isEqualTo(WalletHistoryReason.INVITE_REWARD);
        assertThat(inviterHistories.get(0).getAmount()).isEqualTo(50);
        List<WalletHistory> inviteeHistories = historiesOf(invitee.getId());
        assertThat(inviteeHistories).hasSize(1);
        assertThat(inviteeHistories.get(0).getReason()).isEqualTo(WalletHistoryReason.INVITE_REWARD);
        assertThat(inviteeHistories.get(0).getAmount()).isEqualTo(50);
        assertThat(inviteeHistories.get(0).getBalanceAfter()).isEqualTo(50);
    }

    private List<WalletHistory> historiesOf(Long userId) {
        return walletHistoryRepository.findHistories(userId, null, null, PageRequest.of(0, 100))
                .getContent();
    }

    private void persistWallet(User owner, int balance) {
        UserWallet wallet = BeanUtils.instantiateClass(UserWallet.class);
        ReflectionTestUtils.setField(wallet, "user", owner);
        ReflectionTestUtils.setField(wallet, "currencyType", CurrencyType.COIN);
        ReflectionTestUtils.setField(wallet, "balance", balance);
        userWalletRepository.save(wallet);
    }
}
