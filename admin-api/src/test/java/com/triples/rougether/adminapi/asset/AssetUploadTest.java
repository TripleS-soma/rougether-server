package com.triples.rougether.adminapi.asset;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.adminapi.asset.service.AssetStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AssetUploadTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    AssetStorageService storage;

    @Test
    @WithMockUser(roles = "ADMIN")
    void 이미지_업로드_성공() throws Exception {
        given(storage.upload(any(), eq("image/png"), eq("items"))).willReturn("items/abc.png");

        MockMultipartFile file = new MockMultipartFile(
                "file", "gear.png", "image/png", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/admin/assets").file(file).param("kind", "items").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("items/abc.png"))
                .andExpect(jsonPath("$.contentType").value("image/png"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 허용안된_이미지_형식이면_400() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "doc.pdf", "application/pdf", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/admin/assets").file(file).param("kind", "items").with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 허용안된_kind면_400() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "x.png", "image/png", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/admin/assets").file(file).param("kind", "unknown").with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 미인증이면_업로드_불가() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "x.png", "image/png", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/admin/assets").file(file).param("kind", "items").with(csrf()))
                .andExpect(status().is3xxRedirection());
    }
}
