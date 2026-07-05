package com.triples.rougether.adminapi.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

// 어드민 인증: admin_users 기반(AdminUserDetailsService) + BCrypt + Thymeleaf form login.
// 유저(소셜 로그인)와 완전히 분리. 로그인 후 메인(/) 은 에셋 업로드 화면.
@Configuration
public class AdminSecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/login",
                                "/css/**", "/js/**", "/images/**",
                                "/admin/health",
                                "/actuator/health",
                                "/actuator/info"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                // 일괄 적재(catalog/슬롯 import)·재화 지급은 curl/스크립트로 호출 → CSRF 제외 (MVP, 인증은 유지).
                .csrf(csrf -> csrf.ignoringRequestMatchers(
                        "/admin/catalog/**", "/admin/items/slots/import", "/admin/users/*/wallets/grant"))
                .formLogin(form -> form
                        .loginPage("/login")
                        .defaultSuccessUrl("/", true)
                        .permitAll()
                )
                .logout(logout -> logout
                        .logoutSuccessUrl("/login?logout")
                        .permitAll()
                );

        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
