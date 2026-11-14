package com.triples.rougether.userapi.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.WithdrawalFeedback;
import com.triples.rougether.domain.member.entity.WithdrawalReason;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.WithdrawalFeedbackRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.Set;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// 실제 커밋과 롤백을 검증하므로 테스트 자체에는 트랜잭션을 두지 않음.
@SpringBootTest
class WithdrawalFeedbackTransactionTest {
    @Autowired private MemberWithdrawalService service;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @MockitoSpyBean private WithdrawalFeedbackRepository feedback;
    private Long userId;
    private Set<Long> existingFeedback;

    @BeforeEach
    void baseline() {
        existingFeedback = feedback.findAll().stream().map(WithdrawalFeedback::getId).collect(Collectors.toSet());
    }

    @AfterEach
    void cleanup() {
        feedback.deleteAll(newFeedback());
        if (userId != null) users.deleteById(userId);
    }

    @Test
    void 선택_사유와_탈퇴가_같이_커밋되고_재요청은_중복_기록하지_않는다() {
        createUser();
        service.withdraw(userId, WithdrawalReason.MISSING_FEATURE);
        assertThat(users.findById(userId).orElseThrow().isDeleted()).isTrue();
        var rows = newFeedback();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getReason()).isEqualTo(WithdrawalReason.MISSING_FEATURE);
        assertThat(rows.getFirst().getResponseDate()).isEqualTo(LocalDate.now(ZoneId.of("Asia/Seoul")));
        assertThatThrownBy(() -> service.withdraw(userId, WithdrawalReason.OTHER))
                .isInstanceOf(BusinessException.class);
        assertThat(newFeedback()).hasSize(1);
    }

    @Test
    void 본문_없는_기존_탈퇴도_성공하고_사유는_미선택으로_집계된다() {
        createUser();
        service.withdraw(userId);
        assertThat(users.findById(userId).orElseThrow().isDeleted()).isTrue();
        assertThat(newFeedback()).singleElement().satisfies(row -> assertThat(row.getReason()).isNull());
    }

    @Test
    void 사유_SQL_저장_직후_실패하면_탈퇴와_익명화도_함께_롤백된다() {
        createUser();
        doAnswer(invocation -> {
            entityManager.persist(invocation.getArgument(0, WithdrawalFeedback.class));
            entityManager.flush();
            assertThat(jdbc.queryForObject("select count(*) from withdrawal_feedback", Integer.class))
                    .isEqualTo(existingFeedback.size() + 1);
            assertThat(jdbc.queryForObject("select deleted_at is not null from users where id = ?",
                    Boolean.class, userId)).isTrue();
            throw new IllegalStateException("사유 저장 후 실패");
        }).when(feedback).save(any(WithdrawalFeedback.class));
        assertThatThrownBy(() -> service.withdraw(userId, WithdrawalReason.INCONVENIENT))
                .isInstanceOf(IllegalStateException.class).hasMessage("사유 저장 후 실패");
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(newFeedback()).isEmpty();
        User user = users.findById(userId).orElseThrow();
        assertThat(user.isDeleted()).isFalse();
        assertThat(user.getEmail()).startsWith("withdrawal-feedback-");
    }

    private void createUser() {
        userId = users.save(User.signUp("withdrawal-feedback-" + UUID.randomUUID() + "@example.test")).getId();
    }

    private List<WithdrawalFeedback> newFeedback() {
        return feedback.findAll().stream().filter(row -> !existingFeedback.contains(row.getId())).toList();
    }
}
