package com.triples.rougether.userapi.global.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

// 웹앱(브라우저)이 API를 부를 수 있게 허용 출처가 설정으로 열리는지, 나머지 출처는 막히는지 확인함.
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:cors-config;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "cors.allowed-origins=https://app.rougether.com,http://localhost:8081"
})
@AutoConfigureMockMvc
class CorsConfigIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 허용_출처의_preflight는_통과하고_출처를_돌려줌() throws Exception {
        mockMvc.perform(options("/api/v1/auth/kakao")
                        .header(HttpHeaders.ORIGIN, "https://app.rougether.com")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://app.rougether.com"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    @Test
    void 허용되지_않은_출처의_preflight는_거부됨() throws Exception {
        mockMvc.perform(options("/api/v1/auth/kakao")
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }
}
