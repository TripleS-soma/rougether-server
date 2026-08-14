package com.triples.rougether.adminapi.asset;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.adminapi.asset.service.AssetAlreadyExistsException;
import com.triples.rougether.adminapi.asset.service.AssetStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    void 원하는_asset_key로_이미지_업로드_성공() throws Exception {
        String requestedKey = "items/gacha/forest-gift-box.png";
        given(storage.upload(any(), eq("image/png"), eq("items"), eq(requestedKey)))
                .willReturn(requestedKey);

        MockMultipartFile file = new MockMultipartFile(
                "file", "gear.png", "image/png", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/admin/assets")
                        .file(file)
                        .param("kind", "items")
                        .param("key", requestedKey)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value(requestedKey))
                .andExpect(jsonPath("$.contentType").value("image/png"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void key를_비우면_기존처럼_자동_발급한다() throws Exception {
        given(storage.upload(any(), eq("image/png"), eq("items"), eq(null)))
                .willReturn("items/generated.png");

        MockMultipartFile file = new MockMultipartFile(
                "file", "gear.png", "image/png", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/admin/assets").file(file).param("kind", "items").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("items/generated.png"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "characters/cat.png",
            "/items/cat.png",
            "items/../characters/cat.png",
            "items//cat.png",
            "items/cat\\face.png",
            "items/cat.png?version=2"
    })
    @WithMockUser(roles = "ADMIN")
    void 잘못된_asset_key면_400(String key) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "gear.png", "image/png", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/admin/assets")
                        .file(file)
                        .param("kind", "items")
                        .param("key", key)
                        .with(csrf()))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(storage);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void content_type과_key_확장자가_다르면_400() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "gear.png", "image/png", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/admin/assets")
                        .file(file)
                        .param("kind", "items")
                        .param("key", "items/gear.webp")
                        .with(csrf()))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(storage);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 이미_존재하는_asset_key면_409() throws Exception {
        String requestedKey = "items/existing.png";
        given(storage.upload(any(), eq("image/png"), eq("items"), eq(requestedKey)))
                .willThrow(new AssetAlreadyExistsException(requestedKey));

        MockMultipartFile file = new MockMultipartFile(
                "file", "gear.png", "image/png", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/admin/assets")
                        .file(file)
                        .param("kind", "items")
                        .param("key", requestedKey)
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSET_KEY_ALREADY_EXISTS"));
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
