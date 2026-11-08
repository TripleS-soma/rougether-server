package com.triples.rougether.adminapi.web;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

// 운영자 알림 작성과 발송 이력 화면을 제공함.
@Controller
public class NotificationCampaignPageController {

    @GetMapping("/notification-campaigns")
    public String notificationCampaignPage(Authentication authentication, Model model) {
        model.addAttribute("username", authentication.getName());
        return "notification-campaigns";
    }
}
