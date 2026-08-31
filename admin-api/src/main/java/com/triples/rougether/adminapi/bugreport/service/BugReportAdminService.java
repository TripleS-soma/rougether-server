package com.triples.rougether.adminapi.bugreport.service;

import com.triples.rougether.adminapi.asset.service.AssetStorageService;
import com.triples.rougether.adminapi.asset.service.StoredAsset;
import com.triples.rougether.adminapi.bugreport.dto.AdminBugReportResponse;
import com.triples.rougether.adminapi.bugreport.dto.AdminBugReportResponse.AdminBugReportReplyResponse;
import com.triples.rougether.adminapi.bugreport.error.BugReportAdminException;
import com.triples.rougether.adminapi.notification.AdminNotificationService;
import com.triples.rougether.domain.admin.entity.AdminUser;
import com.triples.rougether.domain.admin.repository.AdminUserRepository;
import com.triples.rougether.domain.bugreport.entity.BugReport;
import com.triples.rougether.domain.bugreport.entity.BugReportImage;
import com.triples.rougether.domain.bugreport.entity.BugReportReply;
import com.triples.rougether.domain.bugreport.entity.BugReportStatus;
import com.triples.rougether.domain.bugreport.repository.BugReportImageRepository;
import com.triples.rougether.domain.bugreport.repository.BugReportReplyRepository;
import com.triples.rougether.domain.bugreport.repository.BugReportRepository;
import com.triples.rougether.domain.notification.entity.NotificationType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

// 어드민 버그 제보 열람·처리 상태 관리 (#213) + 답장 (#348).
@Service
public class BugReportAdminService {

    // 답장 push 문구. 답장 내용 미리보기는 알림함 body 로도 쓰여 짧게 자른다(전문은 앱 내 제보 목록에서).
    static final String REPLY_PUSH_TITLE = "보내주신 제보에 답변이 도착했어요";
    private static final int REPLY_PUSH_BODY_MAX = 200;

    private final BugReportRepository bugReportRepository;
    private final BugReportImageRepository bugReportImageRepository;
    private final BugReportReplyRepository bugReportReplyRepository;
    private final AdminUserRepository adminUserRepository;
    private final AdminNotificationService adminNotificationService;
    private final AssetStorageService assetStorageService;

    public BugReportAdminService(BugReportRepository bugReportRepository,
                                 BugReportImageRepository bugReportImageRepository,
                                 BugReportReplyRepository bugReportReplyRepository,
                                 AdminUserRepository adminUserRepository,
                                 AdminNotificationService adminNotificationService,
                                 AssetStorageService assetStorageService) {
        this.bugReportRepository = bugReportRepository;
        this.bugReportImageRepository = bugReportImageRepository;
        this.bugReportReplyRepository = bugReportReplyRepository;
        this.adminUserRepository = adminUserRepository;
        this.adminNotificationService = adminNotificationService;
        this.assetStorageService = assetStorageService;
    }

    @Transactional(readOnly = true)
    public List<AdminBugReportResponse> getReports(BugReportStatus status) {
        List<BugReport> reports = status == null
                ? bugReportRepository.findAllWithUserOrderByIdDesc()
                : bugReportRepository.findByStatusWithUserOrderByIdDesc(status);
        Map<Long, List<String>> keysByReportId = screenshotKeysByReportId(reports);
        Map<Long, List<AdminBugReportReplyResponse>> repliesByReportId = repliesByReportId(reports);
        return reports.stream()
                .map(report -> AdminBugReportResponse.of(report,
                        keysByReportId.getOrDefault(report.getId(), List.of()),
                        repliesByReportId.getOrDefault(report.getId(), List.of())))
                .toList();
    }

    @Transactional
    public AdminBugReportResponse changeStatus(Long id, BugReportStatus status) {
        BugReport report = getReport(id);
        report.changeStatus(status);
        return toResponse(report);
    }

    // 답장 저장 + (선택) 상태 변경 + 알림 발송(#348)을 한 트랜잭션으로. 알림 내역은 여기서 함께 저장되고
    // FCM push 는 커밋 후 best-effort 로 나간다(AdminNotificationService).
    @Transactional
    public AdminBugReportResponse reply(Long id, String content, BugReportStatus status, String adminUsername) {
        BugReport report = getReport(id);
        AdminUser admin = adminUserRepository.findByUsername(adminUsername)
                .orElseThrow(() -> new BugReportAdminException(
                        "ADMIN_NOT_FOUND", "어드민 계정을 찾을 수 없습니다: " + adminUsername, 401));
        bugReportReplyRepository.save(BugReportReply.of(report, admin, content));
        if (status != null) {
            report.changeStatus(status);
        }
        adminNotificationService.send(report.getUser().getId(), NotificationType.BUG_REPORT_REPLY,
                REPLY_PUSH_TITLE, pushBody(content), report.getId());
        return toResponse(report);
    }

    @Transactional(readOnly = true)
    public StoredAsset getScreenshot(String key) {
        if (key == null
                || !key.startsWith("bug-reports/")
                || !bugReportImageRepository.existsByStorageKey(key)) {
            throw new BugReportAdminException(
                    "BUG_REPORT_SCREENSHOT_NOT_FOUND", "존재하지 않는 버그 제보 스크린샷입니다.", 404);
        }
        try {
            return assetStorageService.read(key);
        } catch (NoSuchKeyException exception) {
            throw new BugReportAdminException(
                    "BUG_REPORT_SCREENSHOT_NOT_FOUND", "존재하지 않는 버그 제보 스크린샷입니다.", 404);
        }
    }

    private BugReport getReport(Long id) {
        return bugReportRepository.findById(id)
                .orElseThrow(() -> new BugReportAdminException("BUG_REPORT_NOT_FOUND", "존재하지 않는 제보입니다: " + id, 404));
    }

    private AdminBugReportResponse toResponse(BugReport report) {
        List<BugReport> single = List.of(report);
        return AdminBugReportResponse.of(report,
                screenshotKeysByReportId(single).getOrDefault(report.getId(), List.of()),
                repliesByReportId(single).getOrDefault(report.getId(), List.of()));
    }

    private static String pushBody(String content) {
        if (content.length() <= REPLY_PUSH_BODY_MAX) {
            return content;
        }
        int end = REPLY_PUSH_BODY_MAX - 1;
        // 이모지 같은 서로게이트 페어 중간을 자르면 깨진 문자열이 저장되므로 경계를 한 칸 물린다
        if (Character.isHighSurrogate(content.charAt(end - 1))) {
            end--;
        }
        return content.substring(0, end) + "…";
    }

    private Map<Long, List<String>> screenshotKeysByReportId(List<BugReport> reports) {
        if (reports.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = reports.stream().map(BugReport::getId).toList();
        Map<Long, List<String>> keys = new HashMap<>();
        for (BugReportImage image : bugReportImageRepository
                .findByBugReportIdInOrderByBugReportIdDescSortOrderAsc(ids)) {
            keys.computeIfAbsent(image.getBugReport().getId(), reportId -> new ArrayList<>())
                    .add(image.getStorageKey());
        }
        return keys;
    }

    private Map<Long, List<AdminBugReportReplyResponse>> repliesByReportId(List<BugReport> reports) {
        if (reports.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = reports.stream().map(BugReport::getId).toList();
        Map<Long, List<AdminBugReportReplyResponse>> replies = new HashMap<>();
        for (BugReportReply reply : bugReportReplyRepository.findWithAdminByBugReportIdIn(ids)) {
            replies.computeIfAbsent(reply.getBugReport().getId(), reportId -> new ArrayList<>())
                    .add(AdminBugReportReplyResponse.of(reply));
        }
        return replies;
    }
}
