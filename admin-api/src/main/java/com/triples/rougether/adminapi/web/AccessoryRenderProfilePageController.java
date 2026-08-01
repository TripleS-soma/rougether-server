package com.triples.rougether.adminapi.web;

import com.triples.rougether.infra.s3.AssetProperties;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

// 캐릭터별 악세사리 합성 위치·크기 편집 화면.
// 목록/변경은 화면 JS가 렌더 프로필 관리 API를 호출한다.
@Controller
public class AccessoryRenderProfilePageController {

    private final AssetProperties assetProperties;

    public AccessoryRenderProfilePageController(AssetProperties assetProperties) {
        this.assetProperties = assetProperties;
    }

    @GetMapping("/accessory-render-profiles")
    public String accessoryRenderProfilePage(Authentication authentication, Model model) {
        model.addAttribute("username", authentication.getName());
        model.addAttribute("s3BaseUrl", assetProperties.publicBaseUrl());
        return "accessory-render-profiles";
    }
}
