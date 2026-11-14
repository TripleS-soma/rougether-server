package com.triples.rougether.adminapi.user;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.domain.member.entity.WithdrawalFeedback;
import com.triples.rougether.domain.member.entity.WithdrawalReason;
import com.triples.rougether.domain.member.repository.WithdrawalFeedbackRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class WithdrawalFeedbackSummaryTest {
    @Autowired private MockMvc mvc;
    @Autowired private WithdrawalFeedbackRepository repository;
    @MockitoBean private Clock clock;

    @BeforeEach
    void clock() {
        when(clock.instant()).thenReturn(Instant.parse("2026-11-11T15:00:00Z"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 최근_30개_KST_일자만_집계하고_미선택과_0건_사유도_표시한다() throws Exception {
        repository.save(WithdrawalFeedback.record(WithdrawalReason.INCONVENIENT, LocalDate.parse("2026-10-14")));
        repository.save(WithdrawalFeedback.record(WithdrawalReason.INCONVENIENT, LocalDate.parse("2026-11-12")));
        repository.save(WithdrawalFeedback.record(null, LocalDate.parse("2026-11-12")));
        repository.save(WithdrawalFeedback.record(WithdrawalReason.OTHER, LocalDate.parse("2026-10-13")));
        repository.save(WithdrawalFeedback.record(WithdrawalReason.OTHER, LocalDate.parse("2026-11-13")));
        mvc.perform(get("/admin/users/withdrawal-feedback"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromDate").value("2026-10-14"))
                .andExpect(jsonPath("$.toDate").value("2026-11-12"))
                .andExpect(jsonPath("$.recordedWithdrawals").value(3))
                .andExpect(jsonPath("$.answeredWithdrawals").value(2))
                .andExpect(jsonPath("$.skippedWithdrawals").value(1))
                .andExpect(jsonPath("$.items[0].reason").value("INCONVENIENT"))
                .andExpect(jsonPath("$.items[0].count").value(2))
                .andExpect(jsonPath("$.items[1].count").value(0))
                .andExpect(jsonPath("$.items[2].count").value(0));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 수집분이_없으면_과거_탈퇴를_추정하지_않고_0건이다() throws Exception {
        mvc.perform(get("/admin/users/withdrawal-feedback"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordedWithdrawals").value(0))
                .andExpect(jsonPath("$.items.length()").value(3));
    }

    @Test
    void 인증하지_않으면_집계를_조회할_수_없다() throws Exception {
        mvc.perform(get("/admin/users/withdrawal-feedback")).andExpect(status().is3xxRedirection());
    }
}
