package com.triples.rougether.adminapi.web;

import com.triples.rougether.adminapi.asset.config.AssetProperties;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

// 에셋 조회 화면. 프론트 개발자가 asset key 를 찾아 복사해 쓰도록 kind 별 썸네일 목록을 보여준다.
// 목록은 화면 JS 가 /admin/assets API 를 호출한다.
@Controller
public class AssetBrowsePageController {

    private final AssetProperties assetProperties;

    public AssetBrowsePageController(AssetProperties assetProperties) {
        this.assetProperties = assetProperties;
    }

    @GetMapping("/assets")
    public String assetBrowsePage(Authentication authentication, Model model) {
        model.addAttribute("username", authentication.getName());
        model.addAttribute("s3BaseUrl", assetProperties.publicBaseUrl());
        return "assets";
    }
}
