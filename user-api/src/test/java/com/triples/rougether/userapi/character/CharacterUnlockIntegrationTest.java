package com.triples.rougether.userapi.character;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.character.entity.Character;
import com.triples.rougether.domain.character.repository.CharacterRepository;
import com.triples.rougether.domain.gacha.entity.Gacha;
import com.triples.rougether.domain.gacha.repository.GachaRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.userapi.gacha.dto.GachaDrawRequest;
import com.triples.rougether.userapi.gacha.error.GachaErrorCode;
import com.triples.rougether.userapi.gacha.service.GachaService;
import com.triples.rougether.userapi.room.dto.RoomResponse;
import com.triples.rougether.userapi.room.service.RoomQueryService;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

// 캐릭터 뽑기 방 레벨 10 해금 guard와 내 방 응답의 해금 필드를 실제 DB 로 검증함(제품 결정 2026-10-14).
@SpringBootTest
@Transactional
class CharacterUnlockIntegrationTest {

    @Autowired private GachaService gachaService;
    @Autowired private RoomQueryService roomQueryService;
    @Autowired private GachaRepository gachaRepository;
    @Autowired private CharacterRepository characterRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserWalletRepository walletRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager em;

    private User user;
    private Gacha characterGacha;

    @BeforeEach
    void setUp() {
        user = userRepository.save(User.signUp());
        walletRepository.save(UserWallet.createWithBalance(user, CurrencyType.COIN, 5000));
        Character bear = characterRepository.save(
                new Character("unlock-bear-" + UUID.randomUUID(), "곰", "characters/bear.png", 10, true));
        characterGacha = gachaRepository.save(new Gacha("unlock-test-" + UUID.randomUUID(), "캐릭터 뽑기",
                CurrencyType.COIN, 500, 1, null, true));
        jdbc.update("INSERT INTO gacha_pool_entries (gacha_id, reward_type, character_id, weight, is_active) "
                + "VALUES (?, 'CHARACTER', ?, 1, true)", characterGacha.getId(), bear.getId());
    }

    @Test
    void 방레벨_9면_캐릭터_뽑기를_403으로_거부하고_코인과_보유를_바꾸지_않는다() {
        room(9, 9);

        assertThatThrownBy(() -> gachaService.draw(user.getId(), characterGacha.getId(), new GachaDrawRequest(1)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(GachaErrorCode.CHARACTER_GACHA_LOCKED);
                    assertThat(e.getErrorCode().code()).isEqualTo("GACHA_CHARACTER_LOCKED");
                    assertThat(e.getErrorCode().status()).isEqualTo(403);
                });
        assertThat(coin()).isEqualTo(5000);
        assertThat(count("user_characters")).isZero();
        assertThat(count("wallet_histories")).isZero();
    }

    @Test
    void 방이_없으면_레벨0으로_보고_캐릭터_뽑기를_거부한다() {
        assertThatThrownBy(() -> gachaService.draw(user.getId(), characterGacha.getId(), new GachaDrawRequest(1)))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(GachaErrorCode.CHARACTER_GACHA_LOCKED));
        assertThat(coin()).isEqualTo(5000);
    }

    @Test
    void 잔액이_부족해도_해금_전이면_잠금_사유를_먼저_알린다() {
        room(3, 3);
        jdbc.update("UPDATE user_wallets SET balance = 0 WHERE user_id = ?", user.getId());
        flushAndClear();

        assertThatThrownBy(() -> gachaService.draw(user.getId(), characterGacha.getId(), new GachaDrawRequest(1)))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(GachaErrorCode.CHARACTER_GACHA_LOCKED));
    }

    @Test
    void 방레벨_10이면_캐릭터를_뽑을_수_있다() {
        room(10, 10);

        assertThat(gachaService.draw(user.getId(), characterGacha.getId(), new GachaDrawRequest(1)).results())
                .singleElement().satisfies(result -> assertThat(result.rewardType()).isEqualTo("CHARACTER"));
        assertThat(coin()).isEqualTo(4500);
        assertThat(count("user_characters")).isEqualTo(1);
    }

    @Test
    void 완료_취소로_레벨이_내려가도_최고_달성_레벨_10이면_해금을_유지한다() {
        room(3, 10);

        gachaService.draw(user.getId(), characterGacha.getId(), new GachaDrawRequest(1));

        assertThat(coin()).isEqualTo(4500);
    }

    @Test
    void 해금_전에도_보상_미리보기는_조회할_수_있다() {
        room(1, 1);

        assertThat(gachaService.getRewards(user.getId(), characterGacha.getId()).items()).hasSize(1);
    }

    @Test
    void 내_방_응답은_최고_달성_레벨_기준_해금_여부와_해금_레벨을_내려준다() {
        room(9, 9);
        RoomResponse locked = roomQueryService.getMyRoom(user.getId());
        assertThat(locked.characterUnlocked()).isFalse();
        assertThat(locked.characterUnlockLevel()).isEqualTo(10);

        jdbc.update("UPDATE personal_rooms SET growth_level = 4, highest_growth_level = 10 WHERE user_id = ?",
                user.getId());
        flushAndClear();
        RoomResponse unlocked = roomQueryService.getMyRoom(user.getId());
        assertThat(unlocked.growthLevel()).isEqualTo(4);
        assertThat(unlocked.characterUnlocked()).isTrue();
    }

    private void room(int level, int highestLevel) {
        jdbc.update("""
                INSERT INTO personal_rooms (user_id, growth_level, growth_points, highest_growth_level,
                                            layout_format, layout_revision, updated_at)
                VALUES (?, ?, 0, ?, 'SLOT_V1', 0, CURRENT_TIMESTAMP)
                """, user.getId(), level, highestLevel);
    }

    // JdbcTemplate 로 바꾼 행을 영속성 컨텍스트의 오래된 엔티티가 가리지 않게 함.
    private void flushAndClear() {
        em.flush();
        em.clear();
    }

    private int coin() {
        return walletRepository.findByUserIdAndCurrencyType(user.getId(), CurrencyType.COIN).orElseThrow().getBalance();
    }

    private long count(String table) {
        em.flush();
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id = ?", Long.class, user.getId());
    }
}
