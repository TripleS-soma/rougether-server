package com.triples.rougether.userapi.todo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.member.repository.WalletHistoryRepository;
import com.triples.rougether.userapi.wallet.service.WalletHistoryRecorder;
import com.triples.rougether.domain.routine.entity.Category;
import com.triples.rougether.domain.routine.entity.PrivacyScope;
import com.triples.rougether.domain.routine.entity.Todo;
import com.triples.rougether.domain.routine.entity.TodoStatus;
import com.triples.rougether.domain.routine.repository.CategoryRepository;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import com.triples.rougether.userapi.routine.reward.service.DailyRewardService;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.userapi.category.error.CategoryErrorCode;
import com.triples.rougether.userapi.global.config.JpaConfig;
import com.triples.rougether.userapi.todo.dto.TodoCreateRequest;
import com.triples.rougether.userapi.todo.dto.TodoResponse;
import com.triples.rougether.userapi.todo.dto.TodoUpdateRequest;
import com.triples.rougether.userapi.todo.error.TodoErrorCode;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
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
    @Autowired
    private WalletHistoryRepository walletHistoryRepository;
    @Autowired
    private RoutineLogRepository routineLogRepository;

    private TodoService service;
    private Long userId;

    @BeforeEach
    void setUp() {
        DailyRewardService dailyRewardService = new DailyRewardService(routineLogRepository,
                todoRepository);
        service = new TodoService(todoRepository, categoryRepository, userRepository,
                userWalletRepository, dailyRewardService,
                new WalletHistoryRecorder(walletHistoryRepository));
        userId = userRepository.save(User.signUp()).getId();
    }

    @Test
    void 등록하면_PENDING_상태에_보상은_0이다() {
        TodoResponse response = service.create(userId,
                new TodoCreateRequest("장보기", "우유, 계란", null, LocalDate.of(2026, 7, 1), null, null, null));

        assertThat(response.status()).isEqualTo(TodoStatus.PENDING);
        assertThat(response.title()).isEqualTo("장보기");
        assertThat(response.dueDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(todoRepository.findById(response.id()).orElseThrow().getRewardAmount())
                .isZero();
    }

    @Test
    void 등록시_dueTime의_초_나노는_0으로_정규화된다() {
        TodoResponse response = service.create(userId,
                new TodoCreateRequest("장보기", null, null, null, LocalTime.of(18, 30, 45), null, null));

        assertThat(response.dueTime()).isEqualTo(LocalTime.of(18, 30));
    }

    @Test
    void 타인_카테고리로_등록하면_CATEGORY_NOT_FOUND() {
        Long otherCategory = persistCategory(otherUser());

        assertThatThrownBy(() -> service.create(userId,
                new TodoCreateRequest("장보기", null, otherCategory, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CategoryErrorCode.CATEGORY_NOT_FOUND);
    }

    @Test
    void 타인_투두_조회는_TODO_NOT_FOUND() {
        Long otherTodo = service.create(otherUserId(), new TodoCreateRequest("남의 투두", null, null, null, null, null, null)).id();

        assertThatThrownBy(() -> service.get(userId, otherTodo))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(TodoErrorCode.TODO_NOT_FOUND);
    }

    @Test
    void 수정은_지정한_필드만_바꾼다() {
        Long todoId = service.create(userId,
                new TodoCreateRequest("장보기", "원래 설명", null, LocalDate.of(2026, 7, 1), null, null, null)).id();

        TodoResponse updated = service.update(userId, todoId,
                new TodoUpdateRequest("청소하기", null, null, null, null));

        assertThat(updated.title()).isEqualTo("청소하기");
        assertThat(updated.description()).isEqualTo("원래 설명");
        assertThat(updated.dueDate()).isEqualTo(LocalDate.of(2026, 7, 1));
    }

    @Test
    void 수정에서_dueTime이_null이면_해제된다() {
        Long todoId = service.create(userId,
                new TodoCreateRequest("장보기", null, null, null, LocalTime.of(9, 0), null, null)).id();

        TodoResponse updated = service.update(userId, todoId,
                new TodoUpdateRequest("청소하기", null, null, null, null));

        assertThat(updated.dueTime()).isNull();
    }

    @Test
    void 수정에서_categoryId가_null이면_미분류로_해제된다() {
        Long categoryId = persistCategory(userRepository.findById(userId).orElseThrow());
        Long todoId = service.create(userId,
                new TodoCreateRequest("장보기", null, categoryId, null, null, null, null)).id();

        TodoResponse updated = service.update(userId, todoId,
                new TodoUpdateRequest("청소하기", null, null, null, null));

        assertThat(updated.categoryId()).isNull();
    }

    @Test
    void 수정에서_dueTime을_지정하면_초_나노가_정규화되어_바뀐다() {
        Long todoId = service.create(userId,
                new TodoCreateRequest("장보기", null, null, null, LocalTime.of(9, 0), null, null)).id();

        TodoResponse updated = service.update(userId, todoId,
                new TodoUpdateRequest(null, null, null, null, LocalTime.of(21, 15, 30)));

        assertThat(updated.dueTime()).isEqualTo(LocalTime.of(21, 15));
    }

    @Test
    void 삭제하면_목록에서_빠진다() {
        Long todoId = service.create(userId, new TodoCreateRequest("장보기", null, null, null, null, null, null)).id();

        service.delete(userId, todoId);

        assertThat(service.list(userId, null, null, null).items()).isEmpty();
    }

    @Test
    void categoryId_필터는_해당_카테고리만_반환한다() {
        Long categoryId = persistCategory(userRepository.findById(userId).orElseThrow());
        service.create(userId, new TodoCreateRequest("분류됨", null, categoryId, null, null, null, null));
        service.create(userId, new TodoCreateRequest("미분류", null, null, null, null, null, null));

        var items = service.list(userId, categoryId, null, null).items();

        assertThat(items).hasSize(1);
        assertThat(items.get(0).title()).isEqualTo("분류됨");
    }

    @Test
    void status_필터는_해당_상태만_반환한다() {
        Long pending = service.create(userId, new TodoCreateRequest("대기", null, null, null, null, null, null)).id();
        Long done = service.create(userId, new TodoCreateRequest("완료", null, null, null, null, null, null)).id();
        persistWallet(userRepository.findById(userId).orElseThrow());
        service.complete(userId, done);

        var items = service.list(userId, null, TodoStatus.PENDING, null).items();

        assertThat(items).extracting(TodoResponse::id).containsExactly(pending);
    }

    @Test
    void dueDate_필터는_해당_마감일만_반환한다() {
        service.create(userId, new TodoCreateRequest("오늘", null, null, LocalDate.of(2026, 7, 1), null, null, null));
        service.create(userId, new TodoCreateRequest("내일", null, null, LocalDate.of(2026, 7, 2), null, null, null));

        var items = service.list(userId, null, null, LocalDate.of(2026, 7, 1)).items();

        assertThat(items).hasSize(1);
        assertThat(items.get(0).title()).isEqualTo("오늘");
    }

    // --- 기기 캘린더 임포트 외부 참조 (#299) ---

    @Test
    void 외부_참조를_함께_보내면_저장되고_응답에_노출된다() {
        TodoResponse response = service.create(userId, imported("팀 회의", "evt-1"));

        assertThat(response.externalSource()).isEqualTo("GOOGLE_CALENDAR");
        assertThat(response.externalId()).isEqualTo("evt-1");
        assertThat(response.status()).isEqualTo(TodoStatus.PENDING);
        Todo saved = todoRepository.findById(response.id()).orElseThrow();
        assertThat(saved.getExternalSource()).isEqualTo("GOOGLE_CALENDAR");
        assertThat(saved.getExternalId()).isEqualTo("evt-1");
    }

    @Test
    void 같은_외부_참조로_다시_만들면_TODO_EXTERNAL_DUPLICATE() {
        service.create(userId, imported("팀 회의", "evt-1"));

        assertThatThrownBy(() -> service.create(userId, imported("팀 회의(재동기화)", "evt-1")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(TodoErrorCode.TODO_EXTERNAL_DUPLICATE);
    }

    @Test
    void 외부_참조_id의_앞뒤_공백은_같은_id로_본다() {
        TodoResponse first = service.create(userId, imported("팀 회의", "  evt-1 "));
        assertThat(first.externalId()).isEqualTo("evt-1");

        assertThatThrownBy(() -> service.create(userId, imported("팀 회의", "evt-1")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(TodoErrorCode.TODO_EXTERNAL_DUPLICATE);
    }

    @Test
    void 지운_임포트_투두와_같은_외부_참조는_다시_만들_수_없다() {
        Long todoId = service.create(userId, imported("팀 회의", "evt-1")).id();
        service.delete(userId, todoId);

        assertThatThrownBy(() -> service.create(userId, imported("팀 회의", "evt-1")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(TodoErrorCode.TODO_EXTERNAL_DUPLICATE);
    }

    @Test
    void 다른_유저는_같은_외부_참조를_가질_수_있다() {
        service.create(userId, imported("팀 회의", "evt-1"));

        TodoResponse other = service.create(otherUserId(), imported("팀 회의", "evt-1"));

        assertThat(other.externalId()).isEqualTo("evt-1");
    }

    @Test
    void 외부_참조를_한쪽만_보내면_TODO_EXTERNAL_REF_INCOMPLETE() {
        assertThatThrownBy(() -> service.create(userId,
                new TodoCreateRequest("팀 회의", null, null, null, null, "GOOGLE_CALENDAR", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(TodoErrorCode.TODO_EXTERNAL_REF_INCOMPLETE);
        assertThatThrownBy(() -> service.create(userId,
                new TodoCreateRequest("팀 회의", null, null, null, null, null, "evt-1")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(TodoErrorCode.TODO_EXTERNAL_REF_INCOMPLETE);
        assertThat(todoRepository.findOwnedWithFilters(userId, null, null, null)).isEmpty();
    }

    @Test
    void 일반_투두는_외부_참조가_없어도_여러_개_만들_수_있다() {
        service.create(userId, new TodoCreateRequest("장보기", null, null, null, null, null, null));
        service.create(userId, new TodoCreateRequest("청소", null, null, null, null, null, null));
        service.create(userId, new TodoCreateRequest("빨래", null, null, null, null, "", ""));

        assertThat(todoRepository.findOwnedWithFilters(userId, null, null, null))
                .hasSize(3)
                .allSatisfy(todo -> {
                    assertThat(todo.getExternalSource()).isNull();
                    assertThat(todo.getExternalId()).isNull();
                });
    }

    @Test
    void 수정해도_외부_참조는_바뀌지_않는다() {
        Long todoId = service.create(userId, imported("팀 회의", "evt-1")).id();

        TodoResponse updated = service.update(userId, todoId,
                new TodoUpdateRequest("팀 회의(변경)", null, null, LocalDate.of(2026, 9, 1), null));

        assertThat(updated.title()).isEqualTo("팀 회의(변경)");
        assertThat(updated.externalSource()).isEqualTo("GOOGLE_CALENDAR");
        assertThat(updated.externalId()).isEqualTo("evt-1");
    }

    @Test
    void 사전_조회를_우회해도_unique_제약이_같은_외부_참조_저장을_막는다() {
        User owner = userRepository.findById(userId).orElseThrow();
        todoRepository.saveAndFlush(Todo.createImported(owner, null, "팀 회의", null, null, null,
                "GOOGLE_CALENDAR", "evt-1"));

        assertThatThrownBy(() -> todoRepository.saveAndFlush(Todo.createImported(owner, null, "팀 회의", null,
                null, null, "GOOGLE_CALENDAR", "evt-1")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // 사전 exists 조회만 false 로 고정한 채 실제 MySQL 에 두 번 저장 — 드라이버 메시지 기반 unique 위반 감지가 409 로 변환되는지
    @Test
    void 사전_조회를_통과한_동시_요청은_실제_unique_위반을_TODO_EXTERNAL_DUPLICATE로_돌려준다() {
        service.create(userId, imported("팀 회의", "evt-1"));
        TodoRepository racing = mock(TodoRepository.class, AdditionalAnswers.delegatesTo(todoRepository));
        doReturn(false).when(racing).existsByUserIdAndExternalSourceAndExternalId(any(), anyString(), anyString());
        TodoService racingService = new TodoService(racing, categoryRepository, userRepository,
                userWalletRepository, new DailyRewardService(routineLogRepository, todoRepository),
                new WalletHistoryRecorder(walletHistoryRepository));

        assertThatThrownBy(() -> racingService.create(userId, imported("팀 회의(재동기화)", "evt-1")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(TodoErrorCode.TODO_EXTERNAL_DUPLICATE);
    }

    private static TodoCreateRequest imported(String title, String externalId) {
        return new TodoCreateRequest(title, null, null, LocalDate.of(2026, 9, 1), null,
                "GOOGLE_CALENDAR", externalId);
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
