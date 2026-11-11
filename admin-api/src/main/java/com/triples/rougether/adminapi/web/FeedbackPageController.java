package com.triples.rougether.adminapi.web;

import java.security.Principal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class FeedbackPageController {
    @GetMapping("/feedback")
    public String page(Model model, Principal principal) {
        model.addAttribute("username", principal.getName());
        return "feedback";
    }
}
