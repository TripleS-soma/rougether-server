package com.triples.rougether.adminapi.web;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

// 유저 관리 화면. 목록/검색은 /admin/users, 재화 지급은 /admin/users/{id}/wallets/grant 를 화면 JS 가 호출한다.
@Controller
public class UserPageController {

    @GetMapping("/users")
    public String userPage(Authentication authentication, Model model) {
        model.addAttribute("username", authentication.getName());
        return "users";
    }
}
