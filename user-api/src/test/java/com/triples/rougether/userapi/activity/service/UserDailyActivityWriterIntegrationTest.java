package com.triples.rougether.userapi.activity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.domain.activity.repository.UserDailyActivityRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.global.security.MemberRole;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:user-daily-activity-writer;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@AutoConfigureMockMvc
class UserDailyActivityWriterIntegrationTest {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserDailyActivityRepository activityRepository;
    @Autowired
    private UserDailyActivityWriter writer;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private TokenService tokenService;
    @Autowired
    private MockMvc mockMvc;

    @Test
    void 기록은_호출자_트랜잭션이_롤백되어도_독립적으로_커밋된다() {
        Long userId = transactionTemplate.execute(tx -> userRepository.save(User.signUp()).getId());
        LocalDate date = LocalDate.of(2026, 8, 29);

        transactionTemplate.executeWithoutResult(tx -> {
            writer.record(userId, date);
            tx.setRollbackOnly();
        });

        assertThat(activityRepository.countByUserIdAndActivityDate(userId, date)).isEqualTo(1);
    }

    @Test
    void SecurityConfig는_유효_JWT의_보호_API_반복_요청을_하루_한_건으로_기록한다() throws Exception {
        User user = userRepository.save(User.signUp());
        String accessToken = tokenService.issueAccessToken(user.getId(), MemberRole.NORMAL);
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));

        mockMvc.perform(get("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk());

        assertThat(activityRepository.countByUserIdAndActivityDate(user.getId(), today)).isEqualTo(1);
    }

    @Test
    void 유효_JWT가_성립하면_404_응답도_활동으로_기록한다() throws Exception {
        User user = userRepository.save(User.signUp());
        String accessToken = tokenService.issueAccessToken(user.getId(), MemberRole.NORMAL);
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));

        mockMvc.perform(get("/api/v1/not-existing-resource")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isNotFound());

        assertThat(activityRepository.countByUserIdAndActivityDate(user.getId(), today)).isEqualTo(1);
    }

    @Test
    void 토큰_없는_요청과_유효하지_않은_토큰과_봇_요청은_기록하지_않는다() throws Exception {
        User human = userRepository.save(User.signUp());
        User bot = userRepository.save(User.bot("daily-activity-filter-bot", "활동 봇", "봇"));
        String botAccessToken = tokenService.issueAccessToken(bot.getId(), MemberRole.NORMAL);
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));

        mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/not-existing-resource")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + botAccessToken))
                .andExpect(status().isNotFound());

        assertThat(activityRepository.countByUserIdAndActivityDate(human.getId(), today)).isZero();
        assertThat(activityRepository.countByUserIdAndActivityDate(bot.getId(), today)).isZero();
    }

    @Test
    void 탈퇴한_사용자의_잔존_JWT_요청은_기록하지_않는다() throws Exception {
        // 탈퇴 유예(access token 최대 30분) 동안 JWT 는 여전히 유효하다 - 필터 경로를 지나더라도
        // 쓰기 SQL 의 deleted_at 가드가 기록을 막아야 한다(#236 잔존 토큰 컨벤션의 관측 버전).
        User withdrawn = userRepository.save(User.signUp());
        String accessToken = tokenService.issueAccessToken(withdrawn.getId(), MemberRole.NORMAL);
        withdrawn.softDelete(Instant.now());
        userRepository.save(withdrawn);
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));

        mockMvc.perform(get("/api/v1/not-existing-resource")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isNotFound());

        assertThat(activityRepository.countByUserIdAndActivityDate(withdrawn.getId(), today)).isZero();
    }
}
