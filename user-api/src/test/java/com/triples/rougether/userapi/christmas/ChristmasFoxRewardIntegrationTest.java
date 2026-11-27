package com.triples.rougether.userapi.christmas;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.triples.rougether.domain.attendance.entity.AttendanceEvent;
import com.triples.rougether.domain.attendance.repository.*;
import com.triples.rougether.domain.character.entity.Character;
import com.triples.rougether.domain.character.entity.UserCharacter;
import com.triples.rougether.domain.character.repository.*;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.policy.SignupWalletPolicy;
import com.triples.rougether.domain.member.repository.*;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.infra.fcm.FcmDelivery;
import com.triples.rougether.userapi.attendance.service.AttendanceEventService;
import com.triples.rougether.userapi.character.service.MyCharacterCommandService;
import com.triples.rougether.userapi.christmas.service.ChristmasEventPolicy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:christmas-fox;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false", "christmas.event.enabled=true", "firebase.credentials-path=",
        "spring.datasource.hikari.maximum-pool-size=4"})
class ChristmasFoxRewardIntegrationTest {
    private static final ZoneId KST=ZoneId.of("Asia/Seoul");
    private static final LocalDate START=LocalDate.of(2026,11,9);
    @Autowired org.springframework.test.web.servlet.MockMvc mockMvc;
    @Autowired com.triples.rougether.userapi.auth.service.TokenService tokens;
    @Autowired AttendanceEventService attendance;
    @Autowired AttendanceEventRepository events;
    @Autowired AttendanceCheckInRepository checkIns;
    @Autowired UserRepository users;
    @Autowired UserWalletRepository wallets;
    @Autowired CharacterRepository characters;
    @Autowired MyCharacterCommandService selection;
    @Autowired com.triples.rougether.userapi.onboarding.service.OnboardingQueryService onboardingQuery;
    @Autowired com.triples.rougether.userapi.onboarding.service.OnboardingCommandService onboardingCommand;
    @Autowired com.triples.rougether.userapi.gacha.service.GachaService gacha;
    @Autowired com.triples.rougether.domain.gacha.repository.GachaRepository gachas;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean Clock kstClock;
    @MockitoBean FcmDelivery fcm;
    @MockitoSpyBean UserCharacterRepository owned;
    @MockitoSpyBean WalletHistoryRepository history;
    @MockitoSpyBean ChristmasEventPolicy policy;
    private User user;
    private Character fox;
    private AttendanceEvent event;

    @BeforeEach void setup() {
        doReturn(KST).when(kstClock).getZone(); today(0);
        user=users.save(User.signUp(UUID.randomUUID()+"@fox.test"));
        wallets.saveAll(SignupWalletPolicy.issueAll(user));
        fox=new Character("fox", "여우", "characters/fox/base-v1.webp",90,true);
        fox.reserveForAttendance(); fox=characters.save(fox);
        event=AttendanceEvent.createCumulativeCoins(ChristmasEventPolicy.CODE,"크리스마스",START,
                LocalDate.of(2026,12,25),30,30,5,60);
        event.configureCharacterReward(fox,3); event=events.save(event);
    }
    @AfterEach void cleanup() {
        reset(owned,history,policy);
        jdbc.update("DELETE FROM wallet_histories WHERE user_id=?",user.getId());
        jdbc.update("DELETE FROM attendance_check_ins WHERE event_id=?",event.getId());
        jdbc.update("DELETE FROM attendance_events WHERE id=?",event.getId());
        jdbc.update("DELETE FROM gacha_pool_entries WHERE character_id=?",fox.getId());
        jdbc.update("DELETE FROM gacha WHERE code='fox_guard_test'");
        jdbc.update("DELETE FROM user_characters WHERE character_id=?",fox.getId());
        jdbc.update("DELETE FROM characters WHERE id=?",fox.getId());
        jdbc.update("DELETE FROM user_wallets WHERE user_id=?",user.getId());
        jdbc.update("DELETE FROM user_daily_activity WHERE user_id IN (SELECT id FROM users WHERE email LIKE '%@fox.test')");
        jdbc.update("DELETE FROM users WHERE email LIKE '%@fox.test'");
        verifyNoInteractions(fcm);
    }
    @Test void 보상수령은_실제JWT_인증계정만_사용하고_요청의_다른userId를_무시한다() throws Exception {
        mockMvc.perform(post("/api/v1/events/attendance/{id}/character-reward", event.getId()))
                .andExpect(status().isUnauthorized());
        setActive(false);
        for (int i=0;i<3;i++) { today(i); attendance.checkIn(user.getId(),event.getId()); }
        setActive(true);
        User other=users.save(User.signUp(UUID.randomUUID()+"@fox.test"));
        String otherToken=tokens.issueAccessToken(other.getId(),com.triples.rougether.userapi.global.security.MemberRole.NORMAL);
        mockMvc.perform(post("/api/v1/events/attendance/{id}/character-reward",event.getId())
                .header("Authorization","Bearer "+otherToken).param("userId",user.getId().toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ATTENDANCE_CHARACTER_REWARD_LOCKED"));
        assertThat(owned.existsByUserIdAndCharacterId(user.getId(),fox.getId())).isFalse();
        String token=tokens.issueAccessToken(user.getId(),com.triples.rougether.userapi.global.security.MemberRole.NORMAL);
        mockMvc.perform(post("/api/v1/events/attendance/{id}/character-reward",event.getId())
                .header("Authorization","Bearer "+token).param("userId",other.getId().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.grantedNow").value(true));
        assertThat(owned.existsByUserIdAndCharacterId(user.getId(),fox.getId())).isTrue();
        assertThat(owned.existsByUserIdAndCharacterId(other.getId(),fox.getId())).isFalse();
    }
    @Test void 누적3일차는_30코인과여우를함께지급하고_레벨0에서도즉시선택한다() {
        for(int day=0;day<3;day++){
            today(day);var result=attendance.checkIn(user.getId(),event.getId());
            assertThat(result.coinRewardAmount()).isEqualTo(30);
            assertThat(result.characterGrantedNow()).isEqualTo(day==2);
            assertThat(result.rewardGrantedNow()).isFalse();
        }
        assertThat(balance()).isEqualTo(SignupWalletPolicy.INITIAL_COIN_BALANCE+90);
        assertThat(owned.findByUserId(user.getId())).hasSize(1);
        var status=attendance.getStatus(user.getId(),event.getId());
        assertThat(status.characterReward().received()).isTrue();
        assertThat(status.characterReward().day()).isEqualTo(3);
        assertThat(status.reward().type()).isEqualTo("NONE");
        selection.select(user.getId(),fox.getId());
        assertThat(owned.findByUserIdAndSelectedTrueAndDeletedAtIsNull(user.getId()).orElseThrow()
                .getCharacter().getId()).isEqualTo(fox.getId());
        assertThat(attendance.checkIn(user.getId(),event.getId()).characterGrantedNow()).isFalse();
        assertThat(count("wallet_histories")).isEqualTo(3);
    }
    @Test void 결석이있어도_누적3회에지급하고_2회에는수령할수없다() {
        today(0);attendance.checkIn(user.getId(),event.getId());
        today(3);attendance.checkIn(user.getId(),event.getId());
        assertThatThrownBy(()->attendance.claimCharacterReward(user.getId(),event.getId()))
                .hasMessageContaining("출석 일차");
        assertThat(owned.findByUserId(user.getId())).isEmpty();
        today(5);var result=attendance.checkIn(user.getId(),event.getId());
        assertThat(result.status().currentStreak()).isEqualTo(1);
        assertThat(result.status().progressDays()).isEqualTo(3);
        assertThat(result.characterGrantedNow()).isTrue();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {3, 30})
    void 이미3일이상출석했으면_완주자도소급수령하고_재시도는중복지급하지않는다(int days) {
        setActive(false);for(int i=0;i<days;i++){today(i);attendance.checkIn(user.getId(),event.getId());}
        setActive(true);int before=balance();
        assertThat(attendance.getStatus(user.getId(),event.getId()).characterReward().claimable()).isTrue();
        // 첫 응답을 잃었다고 가정하고 같은 명령을 다시 보냄.
        assertThat(attendance.claimCharacterReward(user.getId(),event.getId()).grantedNow()).isTrue();
        assertThat(attendance.claimCharacterReward(user.getId(),event.getId()).grantedNow()).isFalse();
        assertThat(attendance.getStatus(user.getId(),event.getId()).characterReward().received()).isTrue();
        assertThat(balance()).isEqualTo(before);
        assertThat(checkIns.countByEventIdAndUserId(event.getId(),user.getId())).isEqualTo(days);
        assertThat(count("wallet_histories")).isEqualTo(days);
        assertThat(owned.findByUserId(user.getId())).hasSize(1);
    }
    @Test void 누적3일차동시요청은_코인과출석과캐릭터가각각한번만생긴다() throws Exception {
        for(int i=0;i<2;i++){today(i);attendance.checkIn(user.getId(),event.getId());}today(2);
        var responses=race(()->attendance.checkIn(user.getId(),event.getId()));
        assertThat(responses).filteredOn(x->x.newCheckIn()).hasSize(1);
        assertThat(responses).filteredOn(x->x.characterGrantedNow()).hasSize(1);
        assertThat(owned.findByUserId(user.getId())).hasSize(1);
        assertThat(count("wallet_histories")).isEqualTo(3);
        assertThat(balance()).isEqualTo(SignupWalletPolicy.INITIAL_COIN_BALANCE+90);
    }
    @Test void 기존3일차_동시소급수령도_한번만지급한다() throws Exception {
        setActive(false);for(int i=0;i<3;i++){today(i);attendance.checkIn(user.getId(),event.getId());}setActive(true);
        var responses=race(()->attendance.claimCharacterReward(user.getId(),event.getId()));
        assertThat(responses).filteredOn(x->x.grantedNow()).hasSize(1);
        assertThat(owned.findByUserId(user.getId())).hasSize(1);
        assertThat(balance()).isEqualTo(SignupWalletPolicy.INITIAL_COIN_BALANCE+90);
    }
    @Test void 누적3일차_원장실패는_캐릭터와코인과출석을모두롤백한다() {
        for(int i=0;i<2;i++){today(i);attendance.checkIn(user.getId(),event.getId());}today(2);
        doThrow(new IllegalStateException("forced history failure")).when(history).save(any());
        assertThatThrownBy(()->attendance.checkIn(user.getId(),event.getId())).isInstanceOf(IllegalStateException.class);
        assertThat(owned.findByUserId(user.getId())).isEmpty();
        assertThat(balance()).isEqualTo(SignupWalletPolicy.INITIAL_COIN_BALANCE+60);
        assertThat(checkIns.countByEventIdAndUserId(event.getId(),user.getId())).isEqualTo(2);
        reset(history);
        assertThat(attendance.checkIn(user.getId(),event.getId()).characterGrantedNow()).isTrue();
    }
    @Test void 캐릭터저장실패도_코인과출석을롤백하고_재시도하면한번만지급한다() {
        for(int i=0;i<2;i++){today(i);attendance.checkIn(user.getId(),event.getId());}today(2);
        doThrow(new IllegalStateException("forced character failure")).when(owned).save(any());
        assertThatThrownBy(()->attendance.checkIn(user.getId(),event.getId())).isInstanceOf(IllegalStateException.class);
        assertThat(balance()).isEqualTo(SignupWalletPolicy.INITIAL_COIN_BALANCE+60);
        assertThat(checkIns.countByEventIdAndUserId(event.getId(),user.getId())).isEqualTo(2);
        reset(owned);attendance.checkIn(user.getId(),event.getId());
        assertThat(owned.findByUserId(user.getId())).hasSize(1);
    }
    @Test void 지급이력은_다른계정에적용되지않고_기보유는코인으로환산하지않는다() {
        owned.save(UserCharacter.create(user,fox));
        for(int i=0;i<3;i++){today(i);assertThat(attendance.checkIn(user.getId(),event.getId()).characterGrantedNow()).isFalse();}
        assertThat(balance()).isEqualTo(SignupWalletPolicy.INITIAL_COIN_BALANCE+90);
        User other=users.save(User.signUp(UUID.randomUUID()+"@fox.test"));
        assertThat(attendance.getStatus(other.getId(),event.getId()).characterReward().received()).isFalse();
        assertThatThrownBy(()->attendance.claimCharacterReward(other.getId(),event.getId())).hasMessageContaining("출석 일차");
        assertThat(owned.findByUserId(other.getId())).isEmpty();
    }
    @Test void 이벤트종료와flagOFF는_기존달성자도소급지급을막고_GET이력만허용한다() {
        setActive(false);for(int i=0;i<3;i++){today(i);attendance.checkIn(user.getId(),event.getId());}setActive(true);
        doReturn(false).when(policy).isOpen(any());
        assertThatThrownBy(()->attendance.claimCharacterReward(user.getId(),event.getId())).hasMessageContaining("참여 기간");
        assertThat(attendance.getStatus(user.getId(),event.getId()).characterReward().claimable()).isFalse();
        reset(policy);today(47);
        assertThatThrownBy(()->attendance.claimCharacterReward(user.getId(),event.getId())).hasMessageContaining("참여 기간");
        assertThat(owned.findByUserId(user.getId())).isEmpty();
    }
    @Test void 여우는_온보딩무료선택과_뽑기풀을통해_우회획득할수없다() {
        assertThat(onboardingQuery.getCharacters().items()).noneMatch(c->c.id().equals(fox.getId()));
        assertThatThrownBy(()->onboardingCommand.selectCharacter(user.getId(),fox.getId()))
                .isInstanceOf(com.triples.rougether.common.error.BusinessException.class);
        var machine=gachas.save(new com.triples.rougether.domain.gacha.entity.Gacha("fox_guard_test","테스트",CurrencyType.COIN,1,1,null,true));
        jdbc.update("INSERT INTO gacha_pool_entries(gacha_id,reward_type,character_id,rarity,weight,is_active) VALUES(?,'CHARACTER',?,'일반',1,TRUE)",machine.getId(),fox.getId());
        assertThat(gacha.getRewards(user.getId(),machine.getId()).items()).isEmpty();
        assertThatThrownBy(()->gacha.draw(user.getId(),machine.getId(),new com.triples.rougether.userapi.gacha.dto.GachaDrawRequest(1)))
                .isInstanceOf(com.triples.rougether.common.error.BusinessException.class);
        assertThat(balance()).isEqualTo(SignupWalletPolicy.INITIAL_COIN_BALANCE);
        assertThat(owned.findByUserId(user.getId())).isEmpty();
    }
    @Test void 삭제된보유이력이있어도_다시지급하지않는다() {
        var prior=owned.save(UserCharacter.create(user,fox));
        jdbc.update("UPDATE user_characters SET deleted_at=CURRENT_TIMESTAMP WHERE id=?",prior.getId());
        for(int i=0;i<3;i++){today(i);assertThat(attendance.checkIn(user.getId(),event.getId()).characterGrantedNow()).isFalse();}
        assertThat(attendance.claimCharacterReward(user.getId(),event.getId()).grantedNow()).isFalse();
        assertThat(owned.findByUserId(user.getId())).hasSize(1);
    }
    private void setActive(boolean active){jdbc.update("UPDATE characters SET is_active=? WHERE id=?",active,fox.getId());}
    private void today(int day){doReturn(START.plusDays(day).atTime(9,0).atZone(KST).toInstant()).when(kstClock).instant();}
    private int balance(){return wallets.findByUserIdAndCurrencyType(user.getId(),CurrencyType.COIN).orElseThrow().getBalance();}
    private long count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE user_id=?",Long.class,user.getId());}
    private <T> List<T> race(Callable<T> task) throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)){
            var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
            Callable<T> run=()->{ready.countDown();start.await();return task.call();};
            var a=pool.submit(run);var b=pool.submit(run);assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
            return List.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS));
        }
    }
}
