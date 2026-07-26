package com.triples.rougether.userapi.bugreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.bugreport.entity.BugReportStatus;
import com.triples.rougether.domain.bugreport.repository.BugReportRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.bugreport.dto.BugReportListResponse;
import com.triples.rougether.userapi.bugreport.dto.BugReportResponse;
import com.triples.rougether.userapi.bugreport.error.BugReportErrorCode;
import com.triples.rougether.userapi.bugreport.service.BugReportService;
import com.triples.rougether.userapi.global.storage.AssetStorageService;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

// 버그 제보 (#213) - 제출(이미지 검증·저장)·내 목록(본인 것만 최신순).
@SpringBootTest
@Transactional
class BugReportServiceTest {

    @TestConfiguration
    static class StubStorageConfig {
        static final AtomicInteger UPLOAD_COUNT = new AtomicInteger();

        @Bean
        @Primary
        AssetStorageService stubAssetStorageService() {
            return (content, contentType, kind) ->
                    kind + "/stub-" + UPLOAD_COUNT.incrementAndGet() + ".png";
        }
    }

    @Autowired private BugReportService bugReportService;
    @Autowired private BugReportRepository bugReportRepository;
    @Autowired private UserRepository userRepository;

    private User user;
    private User other;

    @BeforeEach
    void setUp() {
        user = userRepository.save(User.signUp("bug-report@rougether.dev"));
        other = userRepository.save(User.signUp("bug-report-other@rougether.dev"));
    }

    private MockMultipartFile image(String name) {
        return new MockMultipartFile("images", name, "image/png", new byte[]{1, 2, 3});
    }

    @Test
    void 제출하면_RECEIVED_상태로_저장되고_스크린샷_키가_반환된다() {
        BugReportResponse response = bugReportService.submit(user.getId(),
                " 완료 버튼 안 눌림 ", "오늘 탭에서 완료가 안 됩니다", "1.2.0", "iPhone 15 / iOS 18",
                List.of(image("a.png"), image("b.png")));

        assertThat(response.status()).isEqualTo(BugReportStatus.RECEIVED);
        assertThat(response.title()).isEqualTo("완료 버튼 안 눌림");
        assertThat(response.screenshotKeys()).hasSize(2)
                .allSatisfy(key -> assertThat(key).startsWith("bug-reports/"));

        // 저장 필드 검증 - trim 된 제목·본문과 메타가 DB 에 그대로 남는다
        var saved = bugReportRepository.findById(response.bugReportId()).orElseThrow();
        assertThat(saved.getTitle()).isEqualTo("완료 버튼 안 눌림");
        assertThat(saved.getContent()).isEqualTo("오늘 탭에서 완료가 안 됩니다");
        assertThat(saved.getAppVersion()).isEqualTo("1.2.0");
        assertThat(saved.getDeviceInfo()).isEqualTo("iPhone 15 / iOS 18");
        assertThat(saved.getStatus()).isEqualTo(BugReportStatus.RECEIVED);
    }

    @Test
    void 이미지_0장과_정확히_3장은_허용된다() {
        BugReportResponse none = bugReportService.submit(user.getId(), "무첨부", "내용", null, null, null);
        assertThat(none.screenshotKeys()).isEmpty();

        BugReportResponse three = bugReportService.submit(user.getId(), "3장 첨부", "내용", null, null,
                List.of(image("1.png"), image("2.png"), image("3.png")));
        assertThat(three.screenshotKeys()).hasSize(3);
    }

    @Test
    void 이미지_4장이거나_비허용_형식이면_400_이고_저장되지_않는다() {
        List<MockMultipartFile> four = List.of(image("1"), image("2"), image("3"), image("4"));
        assertThatThrownBy(() -> bugReportService.submit(user.getId(), "t", "c", null, null,
                List.copyOf(four)))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(BugReportErrorCode.BUG_REPORT_IMAGE_INVALID));

        MockMultipartFile gif = new MockMultipartFile("images", "x.gif", "image/gif", new byte[]{1});
        assertThatThrownBy(() -> bugReportService.submit(user.getId(), "t", "c", null, null, List.of(gif)))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(BugReportErrorCode.BUG_REPORT_IMAGE_INVALID));

        assertThat(bugReportRepository.findByUserIdOrderByIdDesc(user.getId())).isEmpty();
    }

    @Test
    void 내_목록은_본인_것만_최신순이고_타_유저_제보는_보이지_않는다() {
        bugReportService.submit(user.getId(), "첫 제보", "내용1", null, null, null);
        bugReportService.submit(user.getId(), "둘째 제보", "내용2", null, null, List.of(image("s.png")));
        bugReportService.submit(other.getId(), "남의 제보", "내용3", null, null, null);

        BugReportListResponse mine = bugReportService.getMyReports(user.getId());
        assertThat(mine.items()).hasSize(2);
        assertThat(mine.items().getFirst().title()).isEqualTo("둘째 제보");
        assertThat(mine.items().getFirst().screenshotKeys()).hasSize(1);
        assertThat(mine.items()).noneSatisfy(item -> assertThat(item.title()).isEqualTo("남의 제보"));
    }
}
