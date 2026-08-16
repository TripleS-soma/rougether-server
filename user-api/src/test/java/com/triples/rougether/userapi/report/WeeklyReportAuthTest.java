package com.triples.rougether.userapi.report;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

// 회고는 본인 데이터라 인증 없이는 뚫리면 안 됨 — 보안 필터가 붙은 전체 스택으로 확인.
@SpringBootTest
@AutoConfigureMockMvc
class WeeklyReportAuthTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 토큰_없이_목록을_조회하면_401() throws Exception {
        mockMvc.perform(get("/api/v1/reports/weekly"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_TOKEN"));
    }

    @Test
    void 토큰_없이_상세를_조회하면_401() throws Exception {
        mockMvc.perform(get("/api/v1/reports/weekly/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_TOKEN"));
    }
}
