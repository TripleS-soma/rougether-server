package com.triples.rougether.userapi.logingift;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.logingift.repository.LoginGiftEventRepository;
import com.triples.rougether.domain.logingift.repository.LoginGiftParticipationRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.policy.SignupWalletPolicy;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.member.repository.WalletHistoryRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.shared.WalletHistoryReason;
import com.triples.rougether.domain.shop.entity.UserItem;
import com.triples.rougether.domain.shop.repository.UserItemRepository;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.global.security.MemberRole;
import com.triples.rougether.userapi.logingift.service.*;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "login-gift.retry.enabled=false")
@AutoConfigureMockMvc
class LoginGiftIntegrationTest {
    private static final Instant START = Instant.parse("2026-11-10T15:00:00Z");
    private static final Instant END = Instant.parse("2026-11-11T15:00:00Z");
    @Autowired LoginGiftAccessRecorder recorder;
    @MockitoSpyBean LoginGiftGrantService grants;
    @Autowired LoginGiftRetryWorker worker;
    @Autowired LoginGiftReceiptService receipts;
    @Autowired LoginGiftEventRepository events;
    @Autowired LoginGiftParticipationRepository participations;
    @Autowired UserRepository users;
    @Autowired UserWalletRepository wallets;
    @Autowired WalletHistoryRepository histories;
    @Autowired TokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired UserItemRepository userItems;
    @MockitoSpyBean LoginGiftCaptureService captures;
    @MockitoSpyBean LoginGiftCampaignCatalog catalog;
    @MockitoBean Clock kstClock;
    private final List<Long> userIds = new ArrayList<>();
    private User user;
    private Long eventId;
    private Long itemId;

    @BeforeEach
    void setup() {
        at(START);
        doReturn(java.time.ZoneId.of("Asia/Seoul")).when(kstClock).getZone();
        var event = events.findByCode("PEPERO_DAY_20261111").orElseThrow();
        eventId = event.getId();
        itemId = jdbc.queryForObject("SELECT reward_item_id FROM login_gift_events WHERE id = ?", Long.class, eventId);
        user = createUser(User.signUp());
    }

    @AfterEach
    void cleanup() {
        for (Long id : userIds) {
            jdbc.update("DELETE FROM wallet_histories WHERE user_id = ?", id);
            jdbc.update("DELETE FROM login_gift_participations WHERE user_id = ?", id);
            jdbc.update("DELETE FROM user_items WHERE user_id = ?", id);
            jdbc.update("DELETE FROM user_wallets WHERE user_id = ?", id);
            jdbc.update("DELETE FROM user_daily_activity WHERE user_id = ?", id);
            jdbc.update("DELETE FROM refresh_tokens WHERE user_id = ?", id);
            jdbc.update("DELETE FROM house_goals WHERE house_id IN (SELECT id FROM house WHERE owner_user_id = ?)", id);
            jdbc.update("DELETE FROM house_members WHERE house_id IN (SELECT id FROM house WHERE owner_user_id = ?)", id);
            jdbc.update("DELETE FROM house WHERE owner_user_id = ?", id);
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
        jdbc.update("UPDATE items SET is_active = TRUE WHERE id = ?", itemId);
    }

    @ParameterizedTest
    @CsvSource({
            "2026-11-10T14:59:59.999999Z, false",
            "2026-11-10T15:00:00Z, true",
            "2026-11-11T14:59:59.999999Z, true",
            "2026-11-11T14:59:59.999999999Z, true",
            "2026-11-11T15:00:00Z, false"
    })
    void 한국_시간_11일의_시작은_포함하고_12일의_시작은_제외한다(String instant, boolean eligible) {
        at(Instant.parse(instant));
        recorder.record(user.getId());
        assertThat(balance()).isEqualTo(SignupWalletPolicy.INITIAL_COIN_BALANCE + (eligible ? 111 : 0));
        assertThat(giftHistoryCount()).isEqualTo(eligible ? 1 : 0);
        assertThat(userItems.findByUserIdAndDeletedAtIsNull(user.getId())).hasSize(eligible ? 1 : 0);
    }

    @Test
    void 재접속과_프로세스_재시작에_해당하는_직접_재시도에도_한번만_지급한다() {
        recorder.record(user.getId());
        recorder.record(user.getId());
        captures.capture(eventId, user.getId(), START);
        grants.grant(eventId, user.getId());
        assertOneGift();
        assertThat(receipts.list(user.getId()).gifts()).singleElement().satisfies(gift -> {
            assertThat(gift.coinAmount()).isEqualTo(111);
            assertThat(gift.grantedAt()).isNotNull();
            assertThat(gift.reward().name()).isEqualTo("11:11 소원 관측기");
        });
    }

    @Test
    void 여러_기기의_동시_접속도_모두_성공하고_보상은_한번이다() throws Exception {
        try (var pool = Executors.newFixedThreadPool(4)) {
            var start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) futures.add(pool.submit(() -> {
                start.await();
                captures.capture(eventId, user.getId(), START);
                grants.grant(eventId, user.getId());
                return null;
            }));
            start.countDown();
            for (var future : futures) future.get(30, TimeUnit.SECONDS);
        }
        assertOneGift();
    }

    @Test
    void 가구_저장후_실패하면_코인과_원장과_가구는_롤백되고_접속기록은_남는다() {
        failAfterSavingItem();
        recorder.record(user.getId());
        assertThat(balance()).isEqualTo(SignupWalletPolicy.INITIAL_COIN_BALANCE);
        assertThat(giftHistoryCount()).isZero();
        assertThat(userItems.findByUserIdAndDeletedAtIsNull(user.getId())).isEmpty();
        assertThat(receipts.list(user.getId()).gifts()).singleElement()
                .satisfies(gift -> assertThat(gift.grantedAt()).isNull());
        at(END.plusSeconds(60));
        worker.retryPending();
        assertOneGift();
    }

    @Test
    void 지급중_클라이언트_연결이_끊겨도_이미_기록한_접속은_복구된다() {
        captures.capture(eventId, user.getId(), END.minusNanos(1000));
        // 접속 커밋 뒤 프로세스가 종료되어 즉시 지급을 못 한 상태를 재현함.
        at(END.plusSeconds(3600));
        worker.retryPending();
        assertOneGift();
    }

    @Test
    void 접속_기록_커밋_전_실패는_503으로_반환하고_재요청으로_복구한다() throws Exception {
        doThrow(new IllegalStateException("capture unavailable")).when(captures).capture(anyLong(), anyLong(), any());
        mvc.perform(get("/api/v1/events/login-gifts").header("Authorization", bearer(user)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("LOGIN_GIFT_CAPTURE_UNAVAILABLE"));
        assertThat(receipts.list(user.getId()).gifts()).isEmpty();
        doCallRealMethod().when(captures).capture(anyLong(), anyLong(), any());
        mvc.perform(get("/api/v1/events/login-gifts").header("Authorization", bearer(user)))
                .andExpect(status().isOk());
        assertOneGift();
    }

    @Test
    void 기존_로그인_JWT의_조회_요청만으로_자동지급하고_응답유실_재시도도_멱등이다() throws Exception {
        String authorization = bearer(user);
        mvc.perform(get("/api/v1/events/login-gifts").header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gifts[0].coinAmount").value(111))
                .andExpect(jsonPath("$.gifts[0].grantedAt").isNotEmpty());
        mvc.perform(get("/api/v1/events/login-gifts").header("Authorization", authorization))
                .andExpect(status().isOk());
        assertOneGift();
    }

    @Test
    void 직접_로그인_성공도_별도_조회없이_접속으로_인정한다() throws Exception {
        mvc.perform(post("/api/v1/auth/dev-login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":" + user.getId() + "}"))
                .andExpect(status().isOk());
        assertOneGift();
    }

    @Test
    void 당일_신규가입도_가입_트랜잭션_커밋후_기본보상과_별도로_자동지급한다() throws Exception {
        var result = mvc.perform(post("/api/v1/auth/dev-login").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isNewUser").value(true)).andReturn();
        Number id = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.userId");
        userIds.add(id.longValue());
        user = users.findById(id.longValue()).orElseThrow();
        assertOneGift();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_histories WHERE user_id = ? AND reason = 'SIGNUP_BONUS'",
                Integer.class, user.getId())).isEqualTo(1);
    }

    @Test
    void 행사_밖에서는_선물_카탈로그_장애가_일반요청을_막지_않는다() {
        doThrow(new IllegalStateException("catalog unavailable")).when(catalog).activeAt(any());
        at(START.minusNanos(1));
        assertThatCode(() -> recorder.record(user.getId())).doesNotThrowAnyException();
        at(END);
        assertThatCode(() -> recorder.record(user.getId())).doesNotThrowAnyException();
        verify(catalog, never()).activeAt(any());
    }

    @Test
    void 마이그레이션_세션이_한국시간이어도_행사_시각은_UTC로_저장되고_세션은_복원된다() throws Exception {
        // 세션 전용 임시 테이블로 실제 운영 테이블을 건드리지 않고 V97의 원문 seed를 실행함.
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            String originalZone;
            try (var result = statement.executeQuery("SELECT @@session.time_zone")) {
                result.next();
                originalZone = result.getString(1);
            }
            try {
                statement.execute("SET SESSION time_zone = '+09:00'");
                statement.execute("CREATE TEMPORARY TABLE items (id BIGINT, asset_key VARCHAR(255))");
                statement.execute("INSERT INTO items VALUES (1, 'items/pepero-day-20261111/furniture/wish-observatory-v1.png')");
                statement.execute("""
                        CREATE TEMPORARY TABLE login_gift_events (
                            code VARCHAR(50), title VARCHAR(120), starts_at TIMESTAMP(6), ends_at TIMESTAMP(6),
                            coin_amount INT, reward_item_id BIGINT, is_enabled BOOLEAN)
                        """);
                String migration;
                try (var source = new org.springframework.core.io.ClassPathResource(
                        "db/migration/V97__add_login_gifts.sql").getInputStream()) {
                    migration = new String(source.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
                for (String sql : migration.substring(migration.indexOf("-- UTC 저장:")).split(";")) {
                    if (!sql.isBlank()) statement.execute(sql);
                }
                try (var result = statement.executeQuery("SELECT UNIX_TIMESTAMP(starts_at), UNIX_TIMESTAMP(ends_at), @@session.time_zone FROM login_gift_events")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getLong(1)).isEqualTo(START.getEpochSecond());
                    assertThat(result.getLong(2)).isEqualTo(END.getEpochSecond());
                    assertThat(result.getString(3)).isEqualTo("+09:00");
                }
            } finally {
                statement.execute("DROP TEMPORARY TABLE IF EXISTS login_gift_events");
                statement.execute("DROP TEMPORARY TABLE IF EXISTS items");
                try (var restore = connection.prepareStatement("SET SESSION time_zone = ?")) {
                    restore.setString(1, originalZone);
                    restore.execute();
                }
            }
        }
    }

    @Test
    void refresh로_발급된_서버_토큰의_주체에게_선물을_지급한다() {
        recorder.recordRefresh(tokens.issueAccessToken(user.getId(), MemberRole.NORMAL));
        assertOneGift();
    }

    @Test
    void 비회원과_위조_JWT는_지급_대상이_아니다() throws Exception {
        mvc.perform(get("/api/v1/events/login-gifts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/events/login-gifts").header("Authorization", "Bearer forged"))
                .andExpect(status().isUnauthorized());
        assertThat(receipts.list(user.getId()).gifts()).isEmpty();
    }

    @Test
    void 봇과_AI와_탈퇴사용자는_지급하지_않는다() {
        var bot = createUser(User.bot("gift-bot", "bot", "test"));
        var ai = createUser(User.ai("ai", "test"));
        jdbc.update("UPDATE users SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", user.getId());
        for (var candidate : List.of(bot, ai, user)) {
            recorder.record(candidate.getId());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM login_gift_participations WHERE user_id = ?",
                    Integer.class, candidate.getId())).isZero();
        }
    }

    @Test
    void 이미_가구를_보유했으면_코인만_추가하고_가구는_중복생성하지_않는다() {
        jdbc.update("INSERT INTO user_items (user_id, item_id, acquired_at) VALUES (?, ?, CURRENT_TIMESTAMP)", user.getId(), itemId);
        recorder.record(user.getId());
        assertOneGift();
    }

    @Test
    void 받은_가구를_판매한_뒤에도_지급기록으로_재지급을_막는다() {
        recorder.record(user.getId());
        jdbc.update("UPDATE user_items SET deleted_at = CURRENT_TIMESTAMP WHERE user_id = ?", user.getId());
        grants.grant(eventId, user.getId());
        assertThat(balance()).isEqualTo(SignupWalletPolicy.INITIAL_COIN_BALANCE + 111);
        assertThat(giftHistoryCount()).isEqualTo(1);
        assertThat(userItems.findByUserIdAndDeletedAtIsNull(user.getId())).isEmpty();
    }

    @Test
    void 한_사람의_지급실패가_다른_참여자의_복구를_막지_않는다() {
        var other = createUser(User.signUp());
        captures.capture(eventId, user.getId(), START);
        captures.capture(eventId, other.getId(), START);
        doThrow(new IllegalStateException("one user failed")).when(grants).grant(eventId, user.getId());
        worker.retryPending();
        assertThat(balance()).isEqualTo(SignupWalletPolicy.INITIAL_COIN_BALANCE);
        assertThat(receipts.list(other.getId()).gifts()).singleElement()
                .satisfies(gift -> assertThat(gift.grantedAt()).isNotNull());
    }

    @Test
    void 내_안내만_확인할_수_있고_재확인으로_지급상태가_변하지_않는다() throws Exception {
        recorder.record(user.getId());
        Long participationId = receipts.list(user.getId()).gifts().getFirst().participationId();
        var other = createUser(User.signUp());
        mvc.perform(post("/api/v1/events/login-gifts/" + participationId + "/acknowledgement")
                        .header("Authorization", bearer(other)))
                .andExpect(status().isNotFound());
        receipts.acknowledge(user.getId(), participationId);
        Instant first = receipts.list(user.getId()).gifts().getFirst().acknowledgedAt();
        at(START.plusSeconds(30));
        receipts.acknowledge(user.getId(), participationId);
        assertThat(receipts.list(user.getId()).gifts().getFirst().acknowledgedAt()).isEqualTo(first);
        assertOneGift();
    }

    @Test
    void 선물은_보관함에_보이고_상점에는_노출되지_않는다() throws Exception {
        recorder.record(user.getId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM items i JOIN themes t ON t.id = i.theme_id "
                + "WHERE i.id = ? AND i.is_active = TRUE AND t.is_active = TRUE", Integer.class, itemId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gacha_pool_entries WHERE item_id = ?", Integer.class, itemId)).isZero();
        assertThat(userItems.findInventoryByUserId(user.getId(), null)).singleElement()
                .satisfies(item -> assertThat(item.getItem().getId()).isEqualTo(itemId));
    }

    private void failAfterSavingItem() {
        // recorder의 접속 시각은 성공하고, 실제 가구 INSERT 뒤 지급 완료 시각 조회에서 실패시킴.
        doReturn(START).doThrow(new IllegalStateException("failure after item insert")).when(kstClock).instant();
    }
    private User createUser(User value) {
        var saved = users.save(value);
        userIds.add(saved.getId());
        wallets.saveAll(SignupWalletPolicy.issueAll(saved));
        return saved;
    }
    private void at(Instant value) { doReturn(value).when(kstClock).instant(); }
    private String bearer(User value) { return "Bearer " + tokens.issueAccessToken(value.getId(), MemberRole.NORMAL); }
    private int balance() { return wallets.findByUserIdAndCurrencyType(user.getId(), CurrencyType.COIN).orElseThrow().getBalance(); }
    private long giftHistoryCount() {
        return histories.findAll().stream().filter(h -> h.getUser().getId().equals(user.getId()))
                .filter(h -> h.getReason() == WalletHistoryReason.LOGIN_GIFT).count();
    }
    private void assertOneGift() {
        assertThat(balance()).isEqualTo(SignupWalletPolicy.INITIAL_COIN_BALANCE + 111);
        assertThat(giftHistoryCount()).isEqualTo(1);
        assertThat(userItems.findByUserIdAndDeletedAtIsNull(user.getId())).hasSize(1);
        assertThat(receipts.list(user.getId()).gifts()).singleElement()
                .satisfies(gift -> assertThat(gift.grantedAt()).isNotNull());
    }
}
