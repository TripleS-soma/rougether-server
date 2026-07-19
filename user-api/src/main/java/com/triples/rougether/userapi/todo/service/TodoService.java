package com.triples.rougether.userapi.todo.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.routine.entity.Category;
import com.triples.rougether.domain.routine.entity.Todo;
import com.triples.rougether.domain.routine.entity.TodoStatus;
import com.triples.rougether.domain.routine.repository.CategoryRepository;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.userapi.category.error.CategoryErrorCode;
import com.triples.rougether.userapi.routine.reward.service.DailyRewardService;
import com.triples.rougether.userapi.todo.dto.TodoCompleteResponse;
import com.triples.rougether.userapi.todo.dto.TodoCreateRequest;
import com.triples.rougether.userapi.todo.dto.TodoListResponse;
import com.triples.rougether.userapi.todo.dto.TodoResponse;
import com.triples.rougether.userapi.todo.dto.TodoUpdateRequest;
import com.triples.rougether.userapi.todo.error.TodoErrorCode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TodoService {

    // KST 고정 — 완료 가능 여부(마감일) 판정 기준
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // 투두 보상: 루틴(10)과 별도로 5코인 고정
    private static final CurrencyType REWARD_CURRENCY = CurrencyType.COIN;
    private static final int REWARD_AMOUNT = 5;

    private final TodoRepository todoRepository;
    private final CategoryRepository categoryRepository;
    private final UserRepository userRepository;
    private final UserWalletRepository userWalletRepository;
    private final DailyRewardService dailyRewardService;

    @Transactional(readOnly = true)
    public TodoListResponse list(Long userId, Long categoryId, TodoStatus status, LocalDate dueDate) {
        return new TodoListResponse(
                todoRepository.findOwnedWithFilters(userId, categoryId, status, dueDate).stream()
                        .map(TodoResponse::from)
                        .toList());
    }

    @Transactional(readOnly = true)
    public TodoResponse get(Long userId, Long todoId) {
        return TodoResponse.from(findOwned(userId, todoId));
    }

    @Transactional
    public TodoResponse create(Long userId, TodoCreateRequest request) {
        User user = userRepository.getReferenceById(userId);
        Category category = request.categoryId() != null
                ? findOwnedCategory(userId, request.categoryId()) : null;
        Todo todo = Todo.create(user, category, request.title(), request.description(),
                request.dueDate());
        return TodoResponse.from(todoRepository.save(todo));
    }

    @Transactional
    public TodoResponse update(Long userId, Long todoId, TodoUpdateRequest request) {
        Todo todo = findOwned(userId, todoId);
        if (request.categoryId() != null) {
            todo.changeCategory(findOwnedCategory(userId, request.categoryId()));
        }
        todo.update(request.title(), request.description(), request.dueDate());
        return TodoResponse.from(todo);
    }

    @Transactional
    public void delete(Long userId, Long todoId) {
        findOwned(userId, todoId).softDelete(Instant.now());
    }

    // 완료: todos + user_wallets 2개 테이블을 한 트랜잭션으로 변경함(재화 정합성)
    @Transactional
    public TodoCompleteResponse complete(Long userId, Long todoId) {
        // 지갑 행 락을 트랜잭션 첫 조회로 선점함 — MySQL REPEATABLE_READ에서 스냅샷이 락 획득 뒤에 잡혀야
        // 동시 완료(루틴·투두)의 상한 카운트가 서로의 커밋을 보고 직렬화됨. 락 이전에 일반 SELECT를 두면 안 됨
        UserWallet wallet = findWalletForUpdate(userId);
        Todo todo = findOwned(userId, todoId);
        if (todo.getStatus() == TodoStatus.COMPLETED) {
            throw new BusinessException(TodoErrorCode.TODO_ALREADY_COMPLETED);
        }

        LocalDate today = LocalDate.now(KST);
        LocalDate dueDate = todo.getDueDate();
        // 마감일이 미래인 투두는 완료 불가. dueDate null은 없는 전제이나 방어적으로 미래 아님으로 취급함
        if (dueDate != null && dueDate.isAfter(today)) {
            throw new BusinessException(TodoErrorCode.TODO_FUTURE_NOT_COMPLETABLE);
        }
        // 과거 마감(또는 null) 완료는 reward_amount=0으로 기록해 취소 시 환불도 0이 되게 함
        int reward = today.equals(dueDate) && dailyRewardService.canReward(userId, today)
                ? REWARD_AMOUNT : 0;

        todo.complete(REWARD_CURRENCY, reward, Instant.now());

        if (reward > 0) {
            wallet.add(reward);
        }

        return TodoCompleteResponse.from(todo);
    }

    // 완료 취소: 코인 차감 + 완료 상태 되돌리기를 한 트랜잭션으로 처리함(재화 정합성)
    @Transactional
    public TodoResponse cancelComplete(Long userId, Long todoId) {
        Todo todo = findOwned(userId, todoId);
        if (todo.getStatus() != TodoStatus.COMPLETED) {
            throw new BusinessException(TodoErrorCode.TODO_NOT_COMPLETED);
        }
        UserWallet wallet = findWalletForUpdate(userId);
        // 음수 잔액 허용 — 회수 정책 확정 전 임시로, 잔액이 보상액보다 적어도 그대로 차감함
        wallet.subtract(todo.getRewardAmount());

        todo.cancelComplete();
        return TodoResponse.from(todo);
    }

    private Todo findOwned(Long userId, Long todoId) {
        return todoRepository.findByIdAndUserIdAndDeletedAtIsNull(todoId, userId)
                .orElseThrow(() -> new BusinessException(TodoErrorCode.TODO_NOT_FOUND));
    }

    private Category findOwnedCategory(Long userId, Long categoryId) {
        return categoryRepository.findByIdAndUserIdAndDeletedAtIsNull(categoryId, userId)
                .orElseThrow(() -> new BusinessException(CategoryErrorCode.CATEGORY_NOT_FOUND));
    }

    private UserWallet findWalletForUpdate(Long userId) {
        return userWalletRepository.findWithLockByUserIdAndCurrencyType(userId, REWARD_CURRENCY)
                .orElseThrow(() -> new BusinessException(TodoErrorCode.WALLET_NOT_FOUND));
    }
}
