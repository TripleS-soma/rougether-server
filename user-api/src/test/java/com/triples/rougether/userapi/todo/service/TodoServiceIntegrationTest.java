package com.triples.rougether.userapi.todo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.routine.entity.Category;
import com.triples.rougether.domain.routine.entity.PrivacyScope;
import com.triples.rougether.domain.routine.entity.TodoStatus;
import com.triples.rougether.domain.routine.repository.CategoryRepository;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.userapi.category.error.CategoryErrorCode;
import com.triples.rougether.userapi.global.config.JpaConfig;
import com.triples.rougether.userapi.todo.dto.TodoCreateRequest;
import com.triples.rougether.userapi.todo.dto.TodoResponse;
import com.triples.rougether.userapi.todo.dto.TodoUpdateRequest;
import com.triples.rougether.userapi.todo.error.TodoErrorCode;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class TodoServiceIntegrationTest {

    @Autowired
    private TodoRepository todoRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserWalletRepository userWalletRepository;

    private TodoService service;
    private Long userId;

    @BeforeEach
    void setUp() {
        service = new TodoService(todoRepository, categoryRepository, userRepository,
                userWalletRepository);
        userId = userRepository.save(User.signUp()).getId();
    }

    @Test
    void 등록하면_PENDING_상태에_보상은_0이다() {
        TodoResponse response = service.create(userId,
                new TodoCreateRequest("장보기", "우유, 계란", null, LocalDate.of(2026, 7, 1)));

        assertThat(response.status()).isEqualTo(TodoStatus.PENDING);
        assertThat(response.title()).isEqualTo("장보기");
        assertThat(response.dueDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(todoRepository.findById(response.id()).orElseThrow().getRewardAmount())
                .isZero();
    }

    @Test
    void 타인_카테고리로_등록하면_CATEGORY_NOT_FOUND() {
        Long otherCategory = persistCategory(otherUser());

        assertThatThrownBy(() -> service.create(userId,
                new TodoCreateRequest("장보기", null, otherCategory, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CategoryErrorCode.CATEGORY_NOT_FOUND);
    }

    @Test
    void 타인_투두_조회는_TODO_NOT_FOUND() {
        Long otherTodo = service.create(otherUserId(), new TodoCreateRequest("남의 투두", null, null, null)).id();

        assertThatThrownBy(() -> service.get(userId, otherTodo))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(TodoErrorCode.TODO_NOT_FOUND);
    }

    @Test
    void 수정은_지정한_필드만_바꾼다() {
        Long todoId = service.create(userId,
                new TodoCreateRequest("장보기", "원래 설명", null, LocalDate.of(2026, 7, 1))).id();

        TodoResponse updated = service.update(userId, todoId,
                new TodoUpdateRequest("청소하기", null, null, null));

        assertThat(updated.title()).isEqualTo("청소하기");
        assertThat(updated.description()).isEqualTo("원래 설명");
        assertThat(updated.dueDate()).isEqualTo(LocalDate.of(2026, 7, 1));
    }

    @Test
    void 삭제하면_목록에서_빠진다() {
        Long todoId = service.create(userId, new TodoCreateRequest("장보기", null, null, null)).id();

        service.delete(userId, todoId);

        assertThat(service.list(userId, null, null, null).items()).isEmpty();
    }

    @Test
    void categoryId_필터는_해당_카테고리만_반환한다() {
        Long categoryId = persistCategory(userRepository.findById(userId).orElseThrow());
        service.create(userId, new TodoCreateRequest("분류됨", null, categoryId, null));
        service.create(userId, new TodoCreateRequest("미분류", null, null, null));

        var items = service.list(userId, categoryId, null, null).items();

        assertThat(items).hasSize(1);
        assertThat(items.get(0).title()).isEqualTo("분류됨");
    }

    @Test
    void status_필터는_해당_상태만_반환한다() {
        Long pending = service.create(userId, new TodoCreateRequest("대기", null, null, null)).id();
        Long done = service.create(userId, new TodoCreateRequest("완료", null, null, null)).id();
        persistWallet(userRepository.findById(userId).orElseThrow());
        service.complete(userId, done);

        var items = service.list(userId, null, TodoStatus.PENDING, null).items();

        assertThat(items).extracting(TodoResponse::id).containsExactly(pending);
    }

    @Test
    void dueDate_필터는_해당_마감일만_반환한다() {
        service.create(userId, new TodoCreateRequest("오늘", null, null, LocalDate.of(2026, 7, 1)));
        service.create(userId, new TodoCreateRequest("내일", null, null, LocalDate.of(2026, 7, 2)));

        var items = service.list(userId, null, null, LocalDate.of(2026, 7, 1)).items();

        assertThat(items).hasSize(1);
        assertThat(items.get(0).title()).isEqualTo("오늘");
    }

    private Long otherUserId() {
        return userRepository.save(User.signUp()).getId();
    }

    private User otherUser() {
        return userRepository.save(User.signUp());
    }

    private Long persistCategory(User owner) {
        return categoryRepository.save(
                Category.create(owner, "집안일", "#FFFFFF", null, 0, PrivacyScope.PRIVATE)).getId();
    }

    private void persistWallet(User owner) {
        UserWallet wallet = BeanUtils.instantiateClass(UserWallet.class);
        ReflectionTestUtils.setField(wallet, "user", owner);
        ReflectionTestUtils.setField(wallet, "currencyType", CurrencyType.COIN);
        ReflectionTestUtils.setField(wallet, "balance", 0);
        userWalletRepository.save(wallet);
    }
}
