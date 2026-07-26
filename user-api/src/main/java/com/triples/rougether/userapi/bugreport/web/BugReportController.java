package com.triples.rougether.userapi.bugreport.web;

import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import com.triples.rougether.userapi.bugreport.dto.BugReportListResponse;
import com.triples.rougether.userapi.bugreport.dto.BugReportResponse;
import com.triples.rougether.userapi.bugreport.service.BugReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "BugReport", description = "버그 제보 관련 API")
@RestController
@RequestMapping("/api/v1")
public class BugReportController {

    private final BugReportService bugReportService;

    public BugReportController(BugReportService bugReportService) {
        this.bugReportService = bugReportService;
    }

    @Operation(summary = "버그 제보 제출",
            description = "앱에서 발견한 버그를 제보합니다. 스크린샷은 선택이며 최대 3장, png·jpeg·webp 형식, "
                    + "각 10MB 이하만 허용됩니다. 제출된 제보는 RECEIVED 상태로 시작하고, "
                    + "처리 현황은 내 제보 목록(GET /api/v1/me/bug-reports)에서 확인할 수 있습니다.")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping(value = "/bug-reports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BugReportResponse submit(@CurrentUser AuthUser user,
                                    @RequestParam("title") @NotBlank @Size(min = 1, max = 100) String title,
                                    @RequestParam("content") @NotBlank @Size(min = 1, max = 2000) String content,
                                    @RequestParam(value = "appVersion", required = false)
                                    @Size(max = 30) String appVersion,
                                    @RequestParam(value = "deviceInfo", required = false)
                                    @Size(max = 100) String deviceInfo,
                                    @RequestParam(value = "images", required = false)
                                    List<MultipartFile> images) {
        return bugReportService.submit(user.id(), title, content, appVersion, deviceInfo, images);
    }

    @Operation(summary = "내 버그 제보 목록",
            description = "내가 제출한 버그 제보를 최신순으로 반환합니다. status 로 처리 현황"
                    + "(RECEIVED 접수, IN_PROGRESS 확인 중, RESOLVED 완료)을 확인할 수 있습니다.")
    @GetMapping("/me/bug-reports")
    public BugReportListResponse getMyReports(@CurrentUser AuthUser user) {
        return bugReportService.getMyReports(user.id());
    }
}
