package com.triples.rougether.userapi.activity.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.activity.repository.UserDailyActivityRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.global.config.JpaConfig;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:user-daily-activity;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class UserDailyActivityRepositoryH2Test {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserDailyActivityRepository activityRepository;

    @Test
    void H2_MySQL_모드에서도_동일일_반복_INSERT는_한_건으로_멱등이다() {
        User user = userRepository.saveAndFlush(User.signUp());
        LocalDate date = LocalDate.of(2026, 8, 29);

        activityRepository.insertIfActiveUser(user.getId(), date);
        activityRepository.insertIfActiveUser(user.getId(), date);

        assertThat(activityRepository.countByUserIdAndActivityDate(user.getId(), date)).isEqualTo(1);
    }

    @Test
    void 탈퇴_사용자와_봇은_활동_기록에서_제외한다() {
        User withdrawn = User.signUp();
        withdrawn.softDelete(Instant.parse("2026-08-29T01:00:00Z"));
        withdrawn = userRepository.saveAndFlush(withdrawn);
        User bot = userRepository.saveAndFlush(User.bot("activity-test-bot", "테스트 봇", "봇"));
        LocalDate date = LocalDate.of(2026, 8, 29);

        activityRepository.insertIfActiveUser(withdrawn.getId(), date);
        activityRepository.insertIfActiveUser(bot.getId(), date);

        assertThat(activityRepository.count()).isZero();
    }
}
