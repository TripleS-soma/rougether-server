package com.triples.rougether.adminapi.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.domain.moderation.repository.BannedWordRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

// 어드민 금칙어 CRUD (#209) - 정규화 저장·중복 409·빈 결과 400·멱등 import.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BannedWordAdminTest {

    @Autowired MockMvc mockMvc;
    @Autowired BannedWordRepository bannedWordRepository;

    @Test
    @WithMockUser(roles = "ADMIN")
    void 등록은_정규화해_저장하고_목록으로_조회된다() throws Exception {
        mockMvc.perform(post("/admin/banned-words")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"word\": \"시 @발\"}").with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.word").value("시발"));

        mockMvc.perform(get("/admin/banned-words"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.word == '시발')]").exists());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 중복_등록은_409_정규화_결과가_비면_400() throws Exception {
        mockMvc.perform(post("/admin/banned-words")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"word\": \"시발\"}").with(csrf()))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/admin/banned-words")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"word\": \"시@발\"}").with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BANNED_WORD_INVALID"));

        mockMvc.perform(post("/admin/banned-words")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"word\": \"★!!\"}").with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 삭제하면_목록에서_빠진다() throws Exception {
        mockMvc.perform(post("/admin/banned-words")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"word\": \"지울단어\"}").with(csrf()))
                .andExpect(status().isCreated());
        Long id = bannedWordRepository.findAllByOrderByWordAsc().stream()
                .filter(word -> word.getWord().equals("지울단어"))
                .findFirst().orElseThrow().getId();

        mockMvc.perform(delete("/admin/banned-words/{id}", id).with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(bannedWordRepository.existsByWord("지울단어")).isFalse();

        mockMvc.perform(delete("/admin/banned-words/{id}", id).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void import_는_멱등이고_CSRF_없이_허용된다() throws Exception {
        String body = "[\"시발\", \"시 발\", \"멀쩡새단어\", \"★!!\"]";
        // 시발/시 발 은 정규화 후 동일 - 첫 요청에서 1건 + 멀쩡새단어 1건, ★!! 는 invalid
        mockMvc.perform(post("/admin/banned-words/import")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(2))
                .andExpect(jsonPath("$.invalid[0]").value("★!!"));

        // 재실행: 전부 skip (멱등)
        mockMvc.perform(post("/admin/banned-words/import")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(0))
                .andExpect(jsonPath("$.skipped").value(3));
    }

    @Test
    void 미인증이면_접근_불가() throws Exception {
        mockMvc.perform(get("/admin/banned-words"))
                .andExpect(status().is3xxRedirection());
    }
}
