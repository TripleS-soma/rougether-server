package com.triples.rougether.adminapi.web;

import com.triples.rougether.adminapi.activity.service.AdminUserActivityService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class UserActivityPageController {
    private final AdminUserActivityService service;

    public UserActivityPageController(AdminUserActivityService service) { this.service = service; }

    @GetMapping("/user-activities")
    public String activities(Authentication authentication, Model model) {
        model.addAttribute("username", authentication.getName());
        model.addAttribute("today", service.today());
        model.addAttribute("minDate", service.today().minusDays(AdminUserActivityService.LOOKBACK_DAYS));
        return "user-activities";
    }

    @GetMapping("/accessory-render-profiles")
    public String retiredAccessoryPage() {
        return "redirect:/user-activities";
    }
}
