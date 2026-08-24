package com.triples.rougether.adminapi.web;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

// AI 조정 추천 관측 화면(#332). 주차별 퍼널 집계는 /admin/recommendations/metrics 를 화면 JS 가 호출한다.
@Controller
public class RecommendationPageController {

    @GetMapping("/recommendations")
    public String recommendationPage(Authentication authentication, Model model) {
        model.addAttribute("username", authentication.getName());
        return "recommendations";
    }
}
