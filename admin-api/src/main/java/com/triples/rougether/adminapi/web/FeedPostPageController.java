package com.triples.rougether.adminapi.web;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

// 피드 관리 화면(#534). 목록·삭제는 /admin/feed-posts 를 화면 JS 가 호출한다.
@Controller
public class FeedPostPageController {

    @GetMapping("/feed-posts")
    public String feedPostPage(Authentication authentication, Model model) {
        model.addAttribute("username", authentication.getName());
        return "feed-posts";
    }
}
