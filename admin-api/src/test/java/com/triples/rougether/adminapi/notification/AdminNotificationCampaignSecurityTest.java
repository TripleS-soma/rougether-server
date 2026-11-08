package com.triples.rougether.adminapi.notification;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.infra.fcm.FcmDelivery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "admin.notification-campaigns.dispatch-enabled=false",
        "firebase.credentials-path=",
        "spring.datasource.url=jdbc:h2:mem:admin-notice-security;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@AutoConfigureMockMvc
class AdminNotificationCampaignSecurityTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean FcmDelivery fcmDelivery;

    @ParameterizedTest
    @ValueSource(strings = {"/notification-campaigns", "/admin/notification-campaigns"})
    void 미인증은_관리화면과_API를_조회하지_못한다(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().is3xxRedirection());
        verifyNoInteractions(fcmDelivery);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/notification-campaigns", "/admin/notification-campaigns"})
    void 일반_사용자_권한은_관리자_알림에_접근하지_못한다(String path) throws Exception {
        mockMvc.perform(get(path).with(user("member").roles("USER")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(fcmDelivery);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/admin/notification-campaigns/preview",
            "/admin/notification-campaigns", "/admin/notification-campaigns/1/send"})
    void 관리자여도_CSRF가_없으면_변경하지_못한다(String path) throws Exception {
        mockMvc.perform(post(path).with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(fcmDelivery);
    }

    @Test
    void CSRF가_있어도_일반_사용자는_발송하지_못한다() throws Exception {
        mockMvc.perform(post("/admin/notification-campaigns/1/send")
                        .with(user("member").roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmed\":true,\"expectedRecipientCount\":1}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(fcmDelivery);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "SUPER_ADMIN"})
    void 두_관리자_역할은_목록을_조회할_수_있고_조회는_푸시를_호출하지_않는다(String role) throws Exception {
        mockMvc.perform(get("/admin/notification-campaigns")
                        .with(user("admin").roles(role)))
                .andExpect(status().isOk());
        verifyNoInteractions(fcmDelivery);
    }
}
