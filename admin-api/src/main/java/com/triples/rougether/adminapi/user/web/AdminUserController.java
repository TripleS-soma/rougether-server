package com.triples.rougether.adminapi.user.web;

import com.triples.rougether.adminapi.user.dto.AdminUserListResponse;
import com.triples.rougether.adminapi.user.service.AdminUserQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 어드민 유저 목록/검색. 유저 관리 화면(/users)의 JS 가 호출한다.
@RestController
@RequestMapping("/admin/users")
public class AdminUserController {

    private static final int MAX_SIZE = 100;

    private final AdminUserQueryService adminUserQueryService;

    public AdminUserController(AdminUserQueryService adminUserQueryService) {
        this.adminUserQueryService = adminUserQueryService;
    }

    @GetMapping
    public AdminUserListResponse getUsers(@RequestParam(required = false) String query,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        int boundedSize = Math.min(Math.max(size, 1), MAX_SIZE);
        return adminUserQueryService.getUsers(query, Math.max(page, 0), boundedSize);
    }
}
