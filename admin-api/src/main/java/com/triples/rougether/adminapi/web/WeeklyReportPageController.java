package com.triples.rougether.adminapi.web;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

// AI 주간 회고 관측 화면. 주차별 집계는 /admin/weekly-reports/metrics 를 화면 JS 가 호출한다.
@Controller
public class WeeklyReportPageController {

    @GetMapping("/weekly-reports")
    public String weeklyReportPage(Authentication authentication, Model model) {
        model.addAttribute("username", authentication.getName());
        return "weekly-reports";
    }
}
