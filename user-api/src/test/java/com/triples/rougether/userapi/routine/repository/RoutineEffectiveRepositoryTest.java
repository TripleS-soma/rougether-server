package com.triples.rougether.userapi.routine.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.userapi.global.config.JpaConfig;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

// findEffectiveOnDay 유효기간 경계 검증 — 분기 겹침/공백이 없는지가 핵심
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class RoutineEffectiveRepositoryTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired
    private RoutineRepository routineRepository;
    @Autowired
    private UserRepository userRepository;

    @PersistenceContext
    private EntityManager em;

    private User user;
    private Long userId;

    @BeforeEach
    void setUp() {
        user = userRepository.save(User.signUp());
        userId = user.getId();
    }

    @Test
    void 생성일_당일부터_유효하고_생성_이전_날짜엔_미포함이다() {
        Long id = persistDaily();
        backdateCreatedAt(id, 5); // 생성일 = 오늘-5

        assertThat(idsOn(today().minusDays(5))).contains(id);
        assertThat(idsOn(today().minusDays(6))).doesNotContain(id);
    }

    @Test
    void 닫힌_버전은_삭제일_전날까지만_유효하다() {
        Long id = persistDaily();
        backdateCreatedAt(id, 20);
        closeAndBackdate(id, 5); // 삭제일 = 오늘-5

        // 닫힌·삭제 버전이라도 자기 유효기간(삭제 전날) 안에서는 포함됨
        assertThat(idsOn(today().minusDays(6))).contains(id);
        // 삭제 당일은 무효 — 분기 시 옛/새 버전이 같은 날 겹치지 않게 하는 경계
        assertThat(idsOn(today().minusDays(5))).doesNotContain(id);
    }

    private List<Long> idsOn(LocalDate date) {
        return routineRepository.findEffectiveOnDay(userId, date).stream()
                .map(Routine::getId).toList();
    }

    private LocalDate today() {
        return LocalDate.now(KST);
    }

    private Long persistDaily() {
        return routineRepository.save(Routine.create(user, null, "루틴", AuthType.CHECK,
                "DAILY", null, null, null, null)).getId();
    }

    private void backdateCreatedAt(Long routineId, int days) {
        em.flush();
        em.createNativeQuery("update routines set created_at = created_at - interval " + days
                + " day where id = " + routineId).executeUpdate();
        em.clear();
    }

    private void closeAndBackdate(Long routineId, int days) {
        Routine routine = routineRepository.findById(routineId).orElseThrow();
        routine.softDelete(Instant.now());
        routineRepository.save(routine);
        em.flush();
        em.createNativeQuery("update routines set deleted_at = deleted_at - interval " + days
                + " day where id = " + routineId).executeUpdate();
        em.clear();
    }
}
