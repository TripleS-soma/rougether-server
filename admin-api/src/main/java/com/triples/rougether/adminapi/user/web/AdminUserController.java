package com.triples.rougether.adminapi.user.web;

import com.triples.rougether.adminapi.user.dto.AdminUserAccountStatus;
import com.triples.rougether.adminapi.user.dto.AdminUserActivityFilter;
import com.triples.rougether.adminapi.user.dto.AdminUserFunnelResponse;
import com.triples.rougether.adminapi.user.dto.AdminUserListResponse;
import com.triples.rougether.adminapi.user.dto.AdminUserOnboardingFilter;
import com.triples.rougether.adminapi.user.dto.AdminUserSummaryResponse;
import com.triples.rougether.adminapi.user.service.AdminUserFunnelService;
import com.triples.rougether.adminapi.user.service.AdminUserQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 어드민 유저 목록/검색·요약·퍼널. 유저 관리 화면(/users)의 JS 가 호출함.
@RestController
@RequestMapping("/admin/users")
public class AdminUserController {

    private static final int MAX_SIZE = 100;

    private final AdminUserQueryService adminUserQueryService;
    private final AdminUserFunnelService adminUserFunnelService;

    public AdminUserController(AdminUserQueryService adminUserQueryService,
                               AdminUserFunnelService adminUserFunnelService) {
        this.adminUserQueryService = adminUserQueryService;
        this.adminUserFunnelService = adminUserFunnelService;
    }

    @GetMapping
    public AdminUserListResponse getUsers(@RequestParam(required = false) String query,
                                          @RequestParam(defaultValue = "ACTIVE")
                                          AdminUserAccountStatus accountStatus,
                                          @RequestParam(defaultValue = "ALL")
                                          AdminUserActivityFilter activity,
                                          @RequestParam(defaultValue = "ALL")
                                          AdminUserOnboardingFilter onboarding,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        int boundedSize = Math.min(Math.max(size, 1), MAX_SIZE);
        return adminUserQueryService.getUsers(
                query, accountStatus, activity, onboarding, Math.max(page, 0), boundedSize);
    }

    @GetMapping("/summary")
    public AdminUserSummaryResponse getSummary() {
        return adminUserQueryService.getSummary();
    }

    // 가입 이후 활성화 퍼널(이탈 지점). signupDays=0 은 전체 기간.
    @GetMapping("/funnel")
    public AdminUserFunnelResponse getFunnel(
            @RequestParam(defaultValue = "" + AdminUserFunnelService.DEFAULT_SIGNUP_DAYS) int signupDays) {
        return adminUserFunnelService.getFunnel(signupDays);
    }
}
