package com.triples.rougether.userapi.todo.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.routine.entity.Todo;
import com.triples.rougether.domain.routine.repository.CategoryRepository;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import com.triples.rougether.userapi.routine.reward.service.DailyRewardService;
import com.triples.rougether.userapi.todo.dto.TodoCreateRequest;
import com.triples.rougether.userapi.todo.error.TodoErrorCode;
import com.triples.rougether.userapi.wallet.service.WalletHistoryRecorder;
import java.sql.SQLException;
import java.time.LocalDate;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

// 기기 캘린더 임포트 동시 요청 경합: 사전 exists 조회를 둘 다 통과한 뒤 한쪽이 unique 에 막히는 경우는
// 실제 커밋 타이밍 재현이 어려워 단위 수준으로 검증 — DataIntegrityViolationException 이 409 로 변환되는지
class TodoServiceImportRaceTest {

    private static final Long USER_ID = 7L;

    private final TodoRepository todoRepository = mock(TodoRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);

    private final TodoService service = new TodoService(todoRepository, mock(CategoryRepository.class),
            userRepository, mock(UserWalletRepository.class), mock(DailyRewardService.class),
            mock(WalletHistoryRecorder.class));

    @Test
    void 사전_조회를_통과했어도_unique_위반이_나면_TODO_EXTERNAL_DUPLICATE로_변환한다() {
        when(userRepository.getReferenceById(USER_ID)).thenReturn(mock(User.class));
        when(todoRepository.existsByUserIdAndExternalSourceAndExternalId(USER_ID, "GOOGLE_CALENDAR", "evt-1"))
                .thenReturn(false);
        when(todoRepository.saveAndFlush(any(Todo.class)))
                .thenThrow(new DataIntegrityViolationException("uk_todos_user_external"));

        assertThatThrownBy(() -> service.create(USER_ID, imported("evt-1")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(TodoErrorCode.TODO_EXTERNAL_DUPLICATE);
    }

    @Test
    void 사전_조회에서_중복이면_저장을_시도하지_않는다() {
        when(userRepository.getReferenceById(USER_ID)).thenReturn(mock(User.class));
        when(todoRepository.existsByUserIdAndExternalSourceAndExternalId(USER_ID, "GOOGLE_CALENDAR", "evt-1"))
                .thenReturn(true);

        assertThatThrownBy(() -> service.create(USER_ID, imported("evt-1")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(TodoErrorCode.TODO_EXTERNAL_DUPLICATE);
        verify(todoRepository, never()).saveAndFlush(any(Todo.class));
        verify(todoRepository, never()).save(any(Todo.class));
    }

    @Test
    void 한쪽이_공백_문자열이면_TODO_EXTERNAL_REF_INCOMPLETE로_거절하고_조회도_하지_않는다() {
        assertThatThrownBy(() -> service.create(USER_ID,
                new TodoCreateRequest("팀 회의", null, null, LocalDate.of(2026, 9, 1), null, "GOOGLE_CALENDAR", "  ")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(TodoErrorCode.TODO_EXTERNAL_REF_INCOMPLETE);
        verify(todoRepository, never()).existsByUserIdAndExternalSourceAndExternalId(any(), any(), any());
        verify(todoRepository, never()).save(any(Todo.class));
    }

    @Test
    void 하이버네이트가_제약_이름을_알려주면_그것으로_중복을_판정한다() {
        when(userRepository.getReferenceById(USER_ID)).thenReturn(mock(User.class));
        when(todoRepository.existsByUserIdAndExternalSourceAndExternalId(USER_ID, "GOOGLE_CALENDAR", "evt-1"))
                .thenReturn(false);
        when(todoRepository.saveAndFlush(any(Todo.class))).thenThrow(new DataIntegrityViolationException(
                "could not execute statement",
                new ConstraintViolationException("dup", new SQLException("Duplicate entry"), "todos.uk_todos_user_external")));

        assertThatThrownBy(() -> service.create(USER_ID, imported("evt-1")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(TodoErrorCode.TODO_EXTERNAL_DUPLICATE);
    }

    @Test
    void 중복이_아닌_무결성_오류는_409로_위장하지_않고_그대로_던진다() {
        when(userRepository.getReferenceById(USER_ID)).thenReturn(mock(User.class));
        when(todoRepository.existsByUserIdAndExternalSourceAndExternalId(USER_ID, "GOOGLE_CALENDAR", "evt-1"))
                .thenReturn(false);
        DataIntegrityViolationException truncation = new DataIntegrityViolationException(
                "could not execute statement", new SQLException("Data too long for column 'external_id'"));
        when(todoRepository.saveAndFlush(any(Todo.class))).thenThrow(truncation);

        assertThatThrownBy(() -> service.create(USER_ID, imported("evt-1"))).isSameAs(truncation);
    }

    @Test
    void externalSource와_externalId는_앞뒤_공백을_떼고_조회_저장한다() {
        when(userRepository.getReferenceById(USER_ID)).thenReturn(mock(User.class));
        when(todoRepository.existsByUserIdAndExternalSourceAndExternalId(eq(USER_ID), any(), any())).thenReturn(false);
        when(todoRepository.saveAndFlush(any(Todo.class))).thenAnswer(inv -> inv.getArgument(0));

        service.create(USER_ID, new TodoCreateRequest("팀 회의", null, null, LocalDate.of(2026, 9, 1), null,
                " GOOGLE_CALENDAR ", "  evt-1 "));

        verify(todoRepository).existsByUserIdAndExternalSourceAndExternalId(USER_ID, "GOOGLE_CALENDAR", "evt-1");
        ArgumentCaptor<Todo> saved = ArgumentCaptor.forClass(Todo.class);
        verify(todoRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getExternalSource()).isEqualTo("GOOGLE_CALENDAR");
        assertThat(saved.getValue().getExternalId()).isEqualTo("evt-1");
    }

    private static TodoCreateRequest imported(String externalId) {
        return new TodoCreateRequest("팀 회의", null, null, LocalDate.of(2026, 9, 1), null,
                "GOOGLE_CALENDAR", externalId);
    }
}
