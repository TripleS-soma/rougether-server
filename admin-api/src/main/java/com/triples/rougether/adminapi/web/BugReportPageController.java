package com.triples.rougether.adminapi.web;

import com.triples.rougether.adminapi.asset.config.AssetProperties;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

// 버그 제보 관리 화면 (#213). 목록·상태 변경은 /admin/bug-reports 를 화면 JS 가 호출한다.
@Controller
public class BugReportPageController {

    private final AssetProperties assetProperties;

    public BugReportPageController(AssetProperties assetProperties) {
        this.assetProperties = assetProperties;
    }

    @GetMapping("/bug-reports")
    public String bugReportPage(Authentication authentication, Model model) {
        model.addAttribute("username", authentication.getName());
        model.addAttribute("s3BaseUrl", assetProperties.publicBaseUrl());
        return "bug-reports";
    }
}
