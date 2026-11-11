package com.triples.rougether.userapi.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.triples.rougether.domain.feedback.entity.AppUpdate;
import com.triples.rougether.domain.feedback.entity.Suggestion;
import com.triples.rougether.domain.feedback.repository.*;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.global.security.MemberRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FeedbackIntegrationTest {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.triples.rougether.userapi.logingift.service.LoginGiftAccessRecorder loginGiftAccessRecorder;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.triples.rougether.userapi.activity.service.UserDailyActivityRecorder activityRecorder;
    @Autowired com.triples.rougether.userapi.member.service.MemberWithdrawalService withdrawal;
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired SuggestionRepository suggestions;
    @Autowired AppUpdateRepository updates;
    @Autowired TokenService tokens;
    @Autowired JdbcTemplate jdbc;
    private User owner;
    private String auth;

    @BeforeEach void setUp() {
        owner = users.save(User.signUp("feedback-owner@test.dev"));
        auth = "Bearer " + tokens.issueAccessToken(owner.getId(), MemberRole.NORMAL);
    }

    @Test void 건의_제출과_본인_내역만_조회한다() throws Exception {
        User other = users.save(User.signUp("feedback-other@test.dev"));
        suggestions.save(Suggestion.create(other, "다른 사람", "비공개 내용"));
        mvc.perform(post("/api/v1/suggestions").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"  원하는 기능  \",\"content\":\"  내용  \"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.title").value("원하는 기능"))
                .andExpect(jsonPath("$.content").value("내용")).andExpect(jsonPath("$.createdAt").exists());
        mvc.perform(get("/api/v1/me/suggestions").header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].title").value("원하는 기능"))
                .andExpect(jsonPath("$.items[0].userId").doesNotExist());
    }

    @Test void 비로그인과_일반회원의_업데이트_쓰기를_차단한다() throws Exception {
        mvc.perform(post("/api/v1/suggestions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"제목\",\"content\":\"내용\"}")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/updates")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/updates").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test void 공백_초과길이_잘못된커서를_거부한다() throws Exception {
        for (String title : new String[]{" ", "x".repeat(101)}) {
            mvc.perform(post("/api/v1/suggestions").header("Authorization", auth)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"" + title + "\",\"content\":\"내용\"}"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/v1/suggestions").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"제목\",\"content\":\"" + "x".repeat(2001) + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/me/suggestions?cursor=-1").header("Authorization", auth))
                .andExpect(status().isBadRequest());
    }

    @Test void 최신순_커서로_건의를_빠짐없이_조회한다() throws Exception {
        Suggestion first = suggestions.save(Suggestion.create(owner, "첫 글", "내용"));
        for (int i = 0; i < 20; i++) suggestions.save(Suggestion.create(owner, "건의" + i, "내용"));
        mvc.perform(get("/api/v1/me/suggestions").header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(20))
                .andExpect(jsonPath("$.items[0].title").value("건의19"))
                .andExpect(jsonPath("$.nextCursor").exists());
        mvc.perform(get("/api/v1/me/suggestions").param("cursor", String.valueOf(first.getId() + 1))
                .header("Authorization", auth)).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].title").value("첫 글"));
    }

    @Test void 게시된_업데이트를_읽고_삭제된_글은_반환하지_않는다() throws Exception {
        AppUpdate entry = updates.saveAndFlush(AppUpdate.create("새 업데이트", "새 기능입니다"));
        mvc.perform(get("/api/v1/updates").header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].title").value("새 업데이트"));
        updates.delete(entry); updates.flush();
        mvc.perform(get("/api/v1/updates").header("Authorization", auth))
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test void 실제_탈퇴시_건의는_즉시_삭제되고_잔여토큰으로_다시_쓸수없다() throws Exception {
        Suggestion item = suggestions.saveAndFlush(Suggestion.create(owner, "제목", "비공개"));
        withdrawal.withdraw(owner.getId());
        assertThat(suggestions.existsById(item.getId())).isFalse();
        mvc.perform(post("/api/v1/suggestions").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"제목\",\"content\":\"내용\"}"))
                .andExpect(status().isNotFound());
    }

    @Test void 계정_물리삭제는_건의를_함께_삭제한다() {
        Suggestion item = suggestions.saveAndFlush(Suggestion.create(owner, "제목", "비공개"));
        jdbc.update("delete from users where id = ?", owner.getId());
        assertThat(jdbc.queryForObject("select count(*) from suggestions where id = ?", Long.class, item.getId())).isZero();
    }
}
