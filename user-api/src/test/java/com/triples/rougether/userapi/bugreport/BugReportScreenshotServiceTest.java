package com.triples.rougether.userapi.bugreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.bugreport.repository.BugReportImageRepository;
import com.triples.rougether.domain.bugreport.repository.BugReportRepository;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.bugreport.error.BugReportErrorCode;
import com.triples.rougether.userapi.bugreport.service.BugReportService;
import com.triples.rougether.userapi.global.storage.AssetStorageService;
import com.triples.rougether.userapi.global.storage.StoredAsset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

@ExtendWith(MockitoExtension.class)
class BugReportScreenshotServiceTest {

    private static final Long USER_ID = 7L;
    private static final String SCREENSHOT_KEY = "bug-reports/private.png";

    @Mock BugReportRepository bugReportRepository;
    @Mock BugReportImageRepository bugReportImageRepository;
    @Mock UserRepository userRepository;
    @Mock AssetStorageService assetStorageService;
    @InjectMocks BugReportService bugReportService;

    @Test
    void 소유권이_확인된_스크린샷만_저장소에서_읽는다() {
        StoredAsset expected = new StoredAsset(new byte[] {1, 2, 3}, "image/png");
        when(bugReportImageRepository.existsByStorageKeyAndBugReport_UserId(SCREENSHOT_KEY, USER_ID))
                .thenReturn(true);
        when(assetStorageService.read(SCREENSHOT_KEY)).thenReturn(expected);

        assertThat(bugReportService.getMyScreenshot(USER_ID, SCREENSHOT_KEY)).isSameAs(expected);
        verify(assetStorageService).read(SCREENSHOT_KEY);
    }

    @Test
    void 타인_키와_비공개_prefix가_아닌_키는_존재를_숨긴다() {
        assertThatThrownBy(() -> bugReportService.getMyScreenshot(USER_ID, SCREENSHOT_KEY))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(BugReportErrorCode.BUG_REPORT_SCREENSHOT_NOT_FOUND));
        assertThatThrownBy(() -> bugReportService.getMyScreenshot(USER_ID, "profile/public.png"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(BugReportErrorCode.BUG_REPORT_SCREENSHOT_NOT_FOUND));

        verify(assetStorageService, never()).read(SCREENSHOT_KEY);
        verify(assetStorageService, never()).read("profile/public.png");
    }

    @Test
    void DB_row만_남고_S3_원본이_없어도_404로_숨긴다() {
        when(bugReportImageRepository.existsByStorageKeyAndBugReport_UserId(SCREENSHOT_KEY, USER_ID))
                .thenReturn(true);
        when(assetStorageService.read(SCREENSHOT_KEY))
                .thenThrow(NoSuchKeyException.builder().message("missing").build());

        assertThatThrownBy(() -> bugReportService.getMyScreenshot(USER_ID, SCREENSHOT_KEY))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(BugReportErrorCode.BUG_REPORT_SCREENSHOT_NOT_FOUND));
    }
}
