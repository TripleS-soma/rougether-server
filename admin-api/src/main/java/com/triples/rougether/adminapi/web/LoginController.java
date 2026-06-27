package com.triples.rougether.adminapi.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

// 로그인 페이지 렌더. 인증 처리(POST /login)·에러·로그아웃 파라미터는 Spring Security 가 담당.
@Controller
public class LoginController {

    @GetMapping("/login")
    public String login() {
        return "login";
    }
}
