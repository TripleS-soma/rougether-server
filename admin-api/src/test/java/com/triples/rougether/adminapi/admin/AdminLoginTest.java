package com.triples.rougether.adminapi.admin;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AdminLoginTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void 헬스_엔드포인트는_인증없이_접근가능() throws Exception {
        mockMvc.perform(get("/admin/health"))
                .andExpect(status().isOk());
    }

    @Test
    void 시드된_어드민_계정으로_폼로그인_성공() throws Exception {
        mockMvc.perform(formLogin("/login").user("admin").password("admin1234"))
                .andExpect(authenticated().withUsername("admin"));
    }

    @Test
    void 잘못된_비밀번호면_인증_실패() throws Exception {
        mockMvc.perform(formLogin("/login").user("admin").password("wrong-password"))
                .andExpect(unauthenticated());
    }
}
