package com.triples.rougether.batch.withdrawal;

import static org.mockito.Mockito.verify;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class WithdrawalPurgeServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    void 탈퇴_사용자의_일별_활동을_사용자_범위로_삭제한다() {
        Long withdrawnUserId = 17L;
        WithdrawalPurgeService service = new WithdrawalPurgeService(jdbcTemplate);

        service.purgeUser(withdrawnUserId, Instant.parse("2030-08-29T00:00:00Z"));

        verify(jdbcTemplate).update(
                "DELETE FROM user_daily_activity WHERE user_id = ?",
                withdrawnUserId);
    }
}
