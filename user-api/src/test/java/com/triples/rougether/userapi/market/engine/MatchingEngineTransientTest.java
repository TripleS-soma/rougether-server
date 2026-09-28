package com.triples.rougether.userapi.market.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;

class MatchingEngineTransientTest {

    @Test
    void 데드락과_락_대기_초과는_일시_오류로_보고_실패_횟수를_쓰지_않는다() {
        assertThat(MatchingEngine.isTransient(new CannotAcquireLockException("lock wait timeout"))).isTrue();
        assertThat(MatchingEngine.isTransient(new PessimisticLockingFailureException("deadlock"))).isTrue();
        assertThat(MatchingEngine.isTransient(new RuntimeException("wrapped",
                new CannotAcquireLockException("lock wait timeout")))).isTrue();
    }

    @Test
    void 제약_위반_같은_오류는_일시_오류가_아니다() {
        assertThat(MatchingEngine.isTransient(new DataIntegrityViolationException("uq_user_items_active"))).isFalse();
        assertThat(MatchingEngine.isTransient(new IllegalStateException("bug"))).isFalse();
    }
}
