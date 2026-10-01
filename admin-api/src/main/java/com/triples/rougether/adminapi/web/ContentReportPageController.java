package com.triples.rougether.adminapi.web;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

// 콘텐츠 신고 대기열 화면(#399). 목록·처리는 /admin/reports 를 화면 JS 가 호출한다.
@Controller
public class ContentReportPageController {

    @GetMapping("/content-reports")
    public String contentReportPage(Authentication authentication, Model model) {
        model.addAttribute("username", authentication.getName());
        return "content-reports";
    }
}
