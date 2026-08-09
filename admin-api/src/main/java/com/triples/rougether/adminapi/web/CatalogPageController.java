package com.triples.rougether.adminapi.web;

import com.triples.rougether.adminapi.asset.config.AssetProperties;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

// 카탈로그(아이템·캐릭터) 사용/미사용 관리 화면. 인증된 어드민만 접근.
// 목록/토글은 화면 JS 가 /admin/catalog API 를 호출한다.
@Controller
public class CatalogPageController {

    private final AssetProperties assetProperties;

    public CatalogPageController(AssetProperties assetProperties) {
        this.assetProperties = assetProperties;
    }

    @GetMapping("/catalog")
    public String catalogPage(Authentication authentication, Model model) {
        model.addAttribute("username", authentication.getName());
        model.addAttribute("s3BaseUrl", assetProperties.publicBaseUrl());
        return "catalog";
    }
}
