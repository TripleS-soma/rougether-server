package com.triples.rougether.userapi.feed;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.triples.rougether.domain.house.entity.HouseMemberStatus;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.feed.dto.FeedAuthorHouseListResponse;
import com.triples.rougether.userapi.feed.service.FeedAuthorHouseCommandService;
import com.triples.rougether.userapi.feed.service.FeedAuthorHouseQueryService;
import com.triples.rougether.userapi.feed.web.FeedAuthorHouseController;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUserArgumentResolver;
import com.triples.rougether.userapi.house.dto.HouseJoinResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FeedAuthorHouseController.class)
@AutoConfigureMockMvc(addFilters = false)
class FeedAuthorHouseControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean FeedAuthorHouseQueryService query;
    @MockitoBean FeedAuthorHouseCommandService commands;
    @MockitoBean CurrentUserArgumentResolver currentUser;
    @MockitoBean TokenService tokens;

    @BeforeEach void auth() throws Exception {
        when(currentUser.supportsParameter(any())).thenReturn(true);
        when(currentUser.resolveArgument(any(), any(), any(), any())).thenReturn(new AuthUser(7L, null));
    }

    @Test void 목록이_없는_경우에도_items_배열을_반환한다() throws Exception {
        when(query.list(7L, 10L)).thenReturn(new FeedAuthorHouseListResponse(List.of()));
        mvc.perform(get("/api/v1/feed/posts/10/houses"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
    }

    @Test void 요청_본문_없이_인증된_회원이_즉시입주한다() throws Exception {
        when(commands.join(7L, 10L, 20L)).thenReturn(HouseJoinResponse.joined(30L, 20L, HouseMemberStatus.ACTIVE));
        mvc.perform(post("/api/v1/feed/posts/10/houses/20/join"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.membershipId").value(30))
                .andExpect(jsonPath("$.pendingApproval").value(false));
        verify(commands).join(7L, 10L, 20L);
    }

    @Test void 양수가_아닌_게시물과_집_ID는_거절한다() throws Exception {
        mvc.perform(get("/api/v1/feed/posts/0/houses")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/feed/posts/10/houses/0/join")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/feed/posts/-1/houses/20/join")).andExpect(status().isBadRequest());
        verifyNoInteractions(query, commands);
    }
}
