package com.triples.rougether.userapi.todo.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.routine.entity.Todo;
import com.triples.rougether.domain.routine.entity.TodoStatus;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import com.triples.rougether.userapi.member.error.MemberErrorCode;
import com.triples.rougether.userapi.routine.dto.RoutineResponse;
import com.triples.rougether.userapi.routine.service.RoutineService;
import com.triples.rougether.userapi.todo.dto.TodoConvertRequest;
import com.triples.rougether.userapi.todo.error.TodoErrorCode;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 투두를 반복 루틴으로 바꾼다. 투두에는 반복 개념이 없어(캘린더에서 가져온 단발 일정 포함) 반복을 붙이려면
// 루틴을 새로 만들고 투두를 지워야 하는데, 앱이 두 호출로 하면 중간 실패 시 둘 다 남거나 둘 다 사라진다.
@Service
@RequiredArgsConstructor
public class TodoConversionService {

    private final TodoRepository todoRepository;
    private final UserRepository userRepository;
    private final RoutineService routineService;

    @Transactional
    public RoutineResponse convertToRoutine(Long userId, Long todoId, TodoConvertRequest request) {
        // 완료/완료 취소와 같은 첫 잠금(user 행)으로 직렬화한다 — 일반 SELECT 보다 먼저 잡아야 REPEATABLE_READ
        // 스냅샷이 잠금 뒤에 생겨, 동시 완료가 지운 투두를 되살리거나 같은 투두가 두 번 전환되지 않는다
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.USER_NOT_FOUND));
        Todo todo = todoRepository.findByIdAndUserIdAndDeletedAtIsNull(todoId, userId)
                .orElseThrow(() -> new BusinessException(TodoErrorCode.TODO_NOT_FOUND));
        // 완료 보상이 남은 채 같은 날 루틴을 다시 완료해 보상을 두 번 받지 않도록, 완료 취소 후에만 허용한다
        if (todo.getStatus() == TodoStatus.COMPLETED) {
            throw new BusinessException(TodoErrorCode.TODO_COMPLETED_NOT_CONVERTIBLE);
        }
        // 루틴 생성(검증 포함)이 먼저 — 실패하면 투두를 건드리지 않은 채 예외로 롤백된다
        RoutineResponse routine = routineService.create(userId, request.toRoutineCreateRequest());
        todo.softDelete(Instant.now());
        return routine;
    }
}
