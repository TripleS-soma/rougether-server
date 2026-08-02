package com.triples.rougether.adminapi.web;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

// 금칙어 관리 화면 (#209). 목록/등록/삭제는 /admin/banned-words 를 화면 JS 가 호출한다.
@Controller
public class BannedWordPageController {

    @GetMapping("/banned-words")
    public String bannedWordPage(Authentication authentication, Model model) {
        model.addAttribute("username", authentication.getName());
        return "banned-words";
    }
}
