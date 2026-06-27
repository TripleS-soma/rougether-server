package com.triples.rougether.adminapi.admin;

import com.triples.rougether.domain.admin.entity.AdminRole;
import com.triples.rougether.domain.admin.entity.AdminUser;
import com.triples.rougether.domain.admin.repository.AdminUserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

// admin.seed.enabled=true 일 때만 동작한다(로컬 application.yml 기본값).
// 운영(mysql) profile 에는 enabled 가 false 라 시드되지 않는다 — 알려진 기본 계정 노출 방지.
@Component
@ConditionalOnProperty(prefix = "admin.seed", name = "enabled", havingValue = "true")
public class AdminUserSeeder implements ApplicationRunner {

    private final AdminUserRepository adminUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final String seedUsername;
    private final String seedPassword;

    public AdminUserSeeder(AdminUserRepository adminUserRepository,
                           PasswordEncoder passwordEncoder,
                           @Value("${admin.seed.username}") String seedUsername,
                           @Value("${admin.seed.password}") String seedPassword) {
        this.adminUserRepository = adminUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.seedUsername = seedUsername;
        this.seedPassword = seedPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (adminUserRepository.findByUsername(seedUsername).isEmpty()) {
            adminUserRepository.save(
                    new AdminUser(seedUsername, passwordEncoder.encode(seedPassword), AdminRole.ADMIN));
        }
    }
}
