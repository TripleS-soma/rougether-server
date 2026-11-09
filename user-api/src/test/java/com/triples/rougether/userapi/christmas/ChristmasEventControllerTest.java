package com.triples.rougether.userapi.christmas;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.christmas.dto.ChristmasEventResponse;
import com.triples.rougether.userapi.christmas.service.ChristmasEventService;
import com.triples.rougether.userapi.christmas.service.ChristmasEventPolicy;
import com.triples.rougether.userapi.christmas.web.ChristmasEventController;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUserArgumentResolver;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ChristmasEventController.class)
@AutoConfigureMockMvc(addFilters=false)
class ChristmasEventControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean ChristmasEventService service;
    @MockitoBean CurrentUserArgumentResolver resolver;
    @MockitoBean TokenService tokens;
    @BeforeEach void setup() throws Exception {
        when(resolver.supportsParameter(any())).thenReturn(true);
        when(resolver.resolveArgument(any(),any(),any(),any())).thenReturn(new AuthUser(7L,null));
    }
    @Test void 메타데이터는_현재계정과안정된이벤트코드로반환한다() throws Exception {
        when(service.get(7L)).thenReturn(new ChristmasEventResponse(ChristmasEventPolicy.CODE,"크리스마스",false,
                ChristmasEventPolicy.STARTS_ON,ChristmasEventPolicy.ENDS_ON,null,null,true));
        mvc.perform(get("/api/v1/events/christmas")).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("christmas_2026"))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.popupDismissed").value(true));
        verify(service).get(7L);
    }
    @Test void 빈본문숨김은204이고_요청본문의다른계정을신뢰하지않는다() throws Exception {
        mvc.perform(post("/api/v1/events/christmas/popup-dismissal")).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/events/christmas/popup-dismissal").contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":99}")).andExpect(status().isNoContent());
        verify(service,times(2)).dismissPopup(7L);
        verify(service,never()).dismissPopup(99L);
    }
}
