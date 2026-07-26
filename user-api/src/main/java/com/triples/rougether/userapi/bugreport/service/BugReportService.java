package com.triples.rougether.userapi.bugreport.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.bugreport.entity.BugReport;
import com.triples.rougether.domain.bugreport.entity.BugReportImage;
import com.triples.rougether.domain.bugreport.repository.BugReportImageRepository;
import com.triples.rougether.domain.bugreport.repository.BugReportRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.bugreport.dto.BugReportListResponse;
import com.triples.rougether.userapi.bugreport.dto.BugReportResponse;
import com.triples.rougether.userapi.bugreport.error.BugReportErrorCode;
import com.triples.rougether.userapi.global.storage.AssetStorageService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

// 버그 제보 (#213) - 제출(스크린샷 최대 3장, S3 업로드) + 내 제보 목록.
// 금칙어 검사는 하지 않음 - 운영자만 보는 텍스트라 차단으로 정보를 잃을 이유가 없음(plan 결정).
@Service
public class BugReportService {

    private static final Set<String> ALLOWED_IMAGE_CONTENT_TYPES =
            Set.of("image/png", "image/jpeg", "image/webp");
    private static final long MAX_IMAGE_SIZE_BYTES = 10L * 1024 * 1024;
    private static final int MAX_IMAGE_COUNT = 3;
    private static final String STORAGE_KIND = "bug-reports";

    private final BugReportRepository bugReportRepository;
    private final BugReportImageRepository bugReportImageRepository;
    private final UserRepository userRepository;
    private final AssetStorageService assetStorageService;

    public BugReportService(BugReportRepository bugReportRepository,
                            BugReportImageRepository bugReportImageRepository,
                            UserRepository userRepository,
                            AssetStorageService assetStorageService) {
        this.bugReportRepository = bugReportRepository;
        this.bugReportImageRepository = bugReportImageRepository;
        this.userRepository = userRepository;
        this.assetStorageService = assetStorageService;
    }

    @Transactional
    public BugReportResponse submit(Long userId, String title, String content,
                                    String appVersion, String deviceInfo, List<MultipartFile> images) {
        List<MultipartFile> screenshots = images == null ? List.of() : images;
        validateImages(screenshots);

        User user = userRepository.getReferenceById(userId);
        BugReport report = bugReportRepository.save(
                BugReport.submit(user, title.trim(), content.trim(),
                        blankToNull(appVersion), blankToNull(deviceInfo)));

        List<String> storageKeys = new ArrayList<>();
        for (int i = 0; i < screenshots.size(); i++) {
            MultipartFile file = screenshots.get(i);
            String key = assetStorageService.upload(readBytes(file), file.getContentType(), STORAGE_KIND);
            bugReportImageRepository.save(BugReportImage.of(report, key, i));
            storageKeys.add(key);
        }
        return BugReportResponse.of(report, storageKeys);
    }

    @Transactional(readOnly = true)
    public BugReportListResponse getMyReports(Long userId) {
        List<BugReport> reports = bugReportRepository.findByUserIdOrderByIdDesc(userId);
        Map<Long, List<String>> keysByReportId = screenshotKeysByReportId(reports);
        List<BugReportResponse> items = reports.stream()
                .map(report -> BugReportResponse.of(report,
                        keysByReportId.getOrDefault(report.getId(), List.of())))
                .toList();
        return new BugReportListResponse(items);
    }

    private void validateImages(List<MultipartFile> images) {
        if (images.size() > MAX_IMAGE_COUNT) {
            throw new BusinessException(BugReportErrorCode.BUG_REPORT_IMAGE_INVALID);
        }
        for (MultipartFile file : images) {
            if (file.isEmpty()
                    || file.getSize() > MAX_IMAGE_SIZE_BYTES
                    || file.getContentType() == null
                    || !ALLOWED_IMAGE_CONTENT_TYPES.contains(file.getContentType())) {
                throw new BusinessException(BugReportErrorCode.BUG_REPORT_IMAGE_INVALID);
            }
        }
    }

    private Map<Long, List<String>> screenshotKeysByReportId(List<BugReport> reports) {
        if (reports.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = reports.stream().map(BugReport::getId).toList();
        Map<Long, List<String>> keys = new HashMap<>();
        for (BugReportImage image : bugReportImageRepository
                .findByBugReportIdInOrderByBugReportIdDescSortOrderAsc(ids)) {
            keys.computeIfAbsent(image.getBugReport().getId(), id -> new ArrayList<>())
                    .add(image.getStorageKey());
        }
        return keys;
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new BusinessException(BugReportErrorCode.BUG_REPORT_IMAGE_INVALID);
        }
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
