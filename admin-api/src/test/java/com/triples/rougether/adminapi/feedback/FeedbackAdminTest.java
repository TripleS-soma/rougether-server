package com.triples.rougether.adminapi.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.triples.rougether.domain.feedback.entity.Suggestion;
import com.triples.rougether.domain.feedback.repository.*;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FeedbackAdminTest {
    @Autowired MockMvc mvc;
    @Autowired SuggestionRepository suggestions;
    @Autowired AppUpdateRepository updates;
    @Autowired UserRepository users;

    @Test @WithMockUser(roles = "ADMIN")
    void 관리자에서_건의를_조회하고_삭제한다() throws Exception {
        User user = users.save(User.signUp("feedback-admin@test.dev"));
        Suggestion item = suggestions.save(Suggestion.create(user, "건의", "원하는 기능"));
        mvc.perform(get("/admin/suggestions")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].userId").value(user.getId()))
                .andExpect(jsonPath("$.items[0].content").value("원하는 기능"));
        mvc.perform(delete("/admin/suggestions/" + item.getId()).with(csrf())).andExpect(status().isNoContent());
        assertThat(suggestions.existsById(item.getId())).isFalse();
        mvc.perform(get("/feedback")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("업데이트 게시")));
    }

    @Test @WithMockUser(roles = "ADMIN")
    void 업데이트_추가와_삭제는_실제_목록에_반영된다() throws Exception {
        mvc.perform(post("/admin/updates").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\" 새 기능 \",\"content\":\"내용\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.title").value("새 기능"));
        var entry = updates.findAll().getLast();
        mvc.perform(get("/admin/updates")).andExpect(jsonPath("$.items[0].id").value(entry.getId()));
        mvc.perform(delete("/admin/updates/" + entry.getId()).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/admin/updates")).andExpect(jsonPath("$.items").isEmpty());
    }

    @Test @WithMockUser(roles = "ADMIN")
    void csrf와_공백_본문_검증이_유지된다() throws Exception {
        mvc.perform(post("/admin/updates").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/admin/suggestions/1")).andExpect(status().isForbidden());
        mvc.perform(post("/admin/updates").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"제목\",\"content\":\" \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test @WithMockUser(roles = "USER")
    void 관리자_아닌_역할은_조회와_삭제를_할수_없다() throws Exception {
        mvc.perform(get("/feedback")).andExpect(status().isForbidden());
        mvc.perform(get("/admin/suggestions")).andExpect(status().isForbidden());
        mvc.perform(post("/admin/updates").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/admin/updates/1").with(csrf())).andExpect(status().isForbidden());
    }

    @Test void 비로그인_관리자_요청은_로그인으로_보낸다() throws Exception {
        mvc.perform(get("/admin/suggestions")).andExpect(status().is3xxRedirection());
    }
}
