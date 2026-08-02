package com.triples.rougether.adminapi.bugreport.service;

import com.triples.rougether.adminapi.bugreport.dto.AdminBugReportResponse;
import com.triples.rougether.adminapi.bugreport.error.BugReportAdminException;
import com.triples.rougether.domain.bugreport.entity.BugReport;
import com.triples.rougether.domain.bugreport.entity.BugReportImage;
import com.triples.rougether.domain.bugreport.entity.BugReportStatus;
import com.triples.rougether.domain.bugreport.repository.BugReportImageRepository;
import com.triples.rougether.domain.bugreport.repository.BugReportRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 어드민 버그 제보 열람·처리 상태 관리 (#213).
@Service
public class BugReportAdminService {

    private final BugReportRepository bugReportRepository;
    private final BugReportImageRepository bugReportImageRepository;

    public BugReportAdminService(BugReportRepository bugReportRepository,
                                 BugReportImageRepository bugReportImageRepository) {
        this.bugReportRepository = bugReportRepository;
        this.bugReportImageRepository = bugReportImageRepository;
    }

    @Transactional(readOnly = true)
    public List<AdminBugReportResponse> getReports(BugReportStatus status) {
        List<BugReport> reports = status == null
                ? bugReportRepository.findAllWithUserOrderByIdDesc()
                : bugReportRepository.findByStatusWithUserOrderByIdDesc(status);
        Map<Long, List<String>> keysByReportId = screenshotKeysByReportId(reports);
        return reports.stream()
                .map(report -> AdminBugReportResponse.of(report,
                        keysByReportId.getOrDefault(report.getId(), List.of())))
                .toList();
    }

    @Transactional
    public AdminBugReportResponse changeStatus(Long id, BugReportStatus status) {
        BugReport report = bugReportRepository.findById(id)
                .orElseThrow(() -> new BugReportAdminException("BUG_REPORT_NOT_FOUND", "존재하지 않는 제보입니다: " + id, 404));
        report.changeStatus(status);
        return AdminBugReportResponse.of(report, screenshotKeysByReportId(List.of(report))
                .getOrDefault(report.getId(), List.of()));
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
}
