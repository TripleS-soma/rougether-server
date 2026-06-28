package com.triples.rougether.adminapi.web;

import com.triples.rougether.adminapi.asset.config.AssetProperties;
import com.triples.rougether.adminapi.content.service.AdminContentService;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.shop.entity.PlacementType;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

// 어드민 메인(/) = 에셋 업로드 화면. 인증된 어드민만 접근.
// 미리보기용 public base URL(설정값)을 함께 내려준다(key + base 조합은 화면 JS 가 수행).
@Controller
public class UploadPageController {

    private final AssetProperties assetProperties;
    private final AdminContentService contentService;

    public UploadPageController(AssetProperties assetProperties, AdminContentService contentService) {
        this.assetProperties = assetProperties;
        this.contentService = contentService;
    }

    @GetMapping("/")
    public String uploadPage(Authentication authentication, Model model) {
        model.addAttribute("username", authentication.getName());
        model.addAttribute("s3BaseUrl", assetProperties.publicBaseUrl());
        model.addAttribute("content", contentService.getCatalog());
        model.addAttribute("placementTypes", PlacementType.values());
        model.addAttribute("currencyTypes", CurrencyType.values());
        return "upload";
    }
}
