package com.triples.rougether.adminapi.user;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminUserListTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    @Test
    @WithMockUser(roles = "ADMIN")
    void 이메일_검색으로_유저를_찾고_지갑_미발급이면_잔액_0() throws Exception {
        userRepository.save(User.signUp("admin-list-target@rougether.dev"));
        userRepository.save(User.signUp("other-user@rougether.dev"));

        mockMvc.perform(get("/admin/users").param("query", "admin-list-target"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].email").value("admin-list-target@rougether.dev"))
                .andExpect(jsonPath("$.items[0].coinBalance").value(0))
                .andExpect(jsonPath("$.items[0].diamondBalance").value(0))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 검색어가_없으면_전체_최신_가입순() throws Exception {
        userRepository.save(User.signUp("admin-list-a@rougether.dev"));
        userRepository.save(User.signUp("admin-list-b@rougether.dev"));

        mockMvc.perform(get("/admin/users").param("query", "admin-list-").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                // id 내림차순 — 나중에 가입한 유저가 먼저
                .andExpect(jsonPath("$.items[0].email").value("admin-list-b@rougether.dev"))
                .andExpect(jsonPath("$.total").value(2));
    }

    @Test
    void 미인증이면_접근_불가() throws Exception {
        mockMvc.perform(get("/admin/users"))
                .andExpect(status().is3xxRedirection());
    }
}
