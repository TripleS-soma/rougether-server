package com.triples.rougether.adminapi.web;

import com.triples.rougether.adminapi.asset.config.AssetProperties;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

// positioned 아이템 기본 슬롯 편집 화면. 인증된 어드민만 접근.
// 목록/변경은 화면 JS 가 /admin/items/slots API 를 호출한다.
@Controller
public class ItemSlotPageController {

    private final AssetProperties assetProperties;

    public ItemSlotPageController(AssetProperties assetProperties) {
        this.assetProperties = assetProperties;
    }

    @GetMapping("/item-slots")
    public String itemSlotPage(Authentication authentication, Model model) {
        model.addAttribute("username", authentication.getName());
        model.addAttribute("s3BaseUrl", assetProperties.publicBaseUrl());
        return "item-slots";
    }
}
