package com.triples.rougether.userapi.category.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.routine.entity.Category;
import com.triples.rougether.domain.routine.entity.PrivacyScope;
import com.triples.rougether.domain.routine.repository.CategoryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

// delete는 softDelete 단일 mutation이라 다단계 부분 커밋 위험은 없음.
// 여기서는 delete가 외부 트랜잭션에 참여(REQUIRES_NEW 아님)해 함께 롤백되는 것만 보장함.
@SpringBootTest
class CategoryDeleteRollbackTest {

    @Autowired
    private CategoryService categoryService;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long createdCategoryId;
    private Long createdUserId;

    @AfterEach
    void cleanUp() {
        // 테스트 트랜잭션이 없어 직접 정리함.
        if (createdCategoryId != null) {
            categoryRepository.deleteById(createdCategoryId);
        }
        if (createdUserId != null) {
            userRepository.deleteById(createdUserId);
        }
    }

    @Test
    void 삭제_트랜잭션_도중_예외가_나면_soft_delete가_롤백된다() {
        User user = userRepository.save(User.signUp());
        createdUserId = user.getId();
        // 차단 루틴이 없어야 softDelete 경로에 진입함
        Category category = categoryRepository.save(
                Category.create(user, "삭제대상", null, null, 0, PrivacyScope.PRIVATE));
        createdCategoryId = category.getId();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            categoryService.delete(createdUserId, createdCategoryId);
            throw new RuntimeException("softDelete 이후 단계 실패");
        })).isInstanceOf(RuntimeException.class);

        Category reloaded = categoryRepository.findById(createdCategoryId).orElseThrow();
        assertThat(reloaded.getDeletedAt()).isNull();
    }
}
