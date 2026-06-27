package com.triples.rougether.adminapi.admin.auth;

import com.triples.rougether.domain.admin.entity.AdminUser;
import com.triples.rougether.domain.admin.repository.AdminUserRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

// admin_users 테이블 기반 어드민 인증. 유저(소셜 로그인)와 완전히 분리된 경로.
@Service
public class AdminUserDetailsService implements UserDetailsService {

    private final AdminUserRepository adminUserRepository;

    public AdminUserDetailsService(AdminUserRepository adminUserRepository) {
        this.adminUserRepository = adminUserRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        AdminUser admin = adminUserRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("admin not found: " + username));

        return User.withUsername(admin.getUsername())
                .password(admin.getPasswordHash())
                .authorities(new SimpleGrantedAuthority("ROLE_" + admin.getRole().name()))
                .build();
    }
}
