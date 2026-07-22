package com.triples.rougether.userapi.house;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUserArgumentResolver;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.entity.HouseMemberStatus;
import com.triples.rougether.domain.room.entity.RoomLayoutFormat;
import com.triples.rougether.userapi.room.dto.RoomRenderResponse;
import com.triples.rougether.userapi.house.dto.HouseCreateResponse;
import com.triples.rougether.userapi.house.dto.HouseDetailResponse;
import com.triples.rougether.userapi.house.dto.HouseJoinDetailResponse;
import com.triples.rougether.userapi.house.dto.HouseJoinResponse;
import com.triples.rougether.userapi.house.dto.HouseListResponse;
import com.triples.rougether.userapi.house.dto.HouseMemberListResponse;
import com.triples.rougether.userapi.house.dto.HousePreviewDetailResponse;
import com.triples.rougether.userapi.house.dto.HousePreviewResponse;
import com.triples.rougether.userapi.house.dto.HouseUpdateResponse;
import com.triples.rougether.userapi.house.dto.InviteCodeResponse;
import com.triples.rougether.userapi.house.dto.TransferOwnershipResponse;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import com.triples.rougether.userapi.house.service.HouseCommandService;
import com.triples.rougether.userapi.house.service.HouseJoinService;
import com.triples.rougether.userapi.house.service.HouseMemberCommandService;
import com.triples.rougether.userapi.house.service.HouseQueryService;
import com.triples.rougether.userapi.house.web.HouseController;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HouseController.class)
@AutoConfigureMockMvc(addFilters = false)
class HouseControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HouseCommandService houseCommandService;

    @MockitoBean
    private HouseJoinService houseJoinService;

    @MockitoBean
    private HouseQueryService houseQueryService;

    @MockitoBean
    private HouseMemberCommandService houseMemberCommandService;

    @MockitoBean
    private CurrentUserArgumentResolver currentUserArgumentResolver;

    // security 컨텍스트의 JwtAuthenticationFilter 가 의존 — slice 테스트에서 mock 필요.
    @MockitoBean
    private TokenService tokenService;

    private void authAsUser7() {
        when(currentUserArgumentResolver.supportsParameter(any())).thenReturn(true);
        when(currentUserArgumentResolver.resolveArgument(any(), any(), any(), any()))
                .thenReturn(new AuthUser(7L, null));
    }

    @Test
    void 집_생성_응답_계약() throws Exception {
        authAsUser7();
        when(houseCommandService.create(eq(7L), any())).thenReturn(
                new HouseCreateResponse(1L, 7L, "ABCD2345", Instant.parse("2026-07-10T00:00:00Z")));

        mockMvc.perform(post("/api/v1/houses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "아침 루틴 하우스", "description": "같이 아침 루틴",
                                 "maxMembers": 6, "goalIds": [1, 2]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.houseId").value(1))
                .andExpect(jsonPath("$.ownerUserId").value(7))
                .andExpect(jsonPath("$.inviteCode").value("ABCD2345"));
    }

    @Test
    void 없는_목표가_섞이면_400과_에러코드를_내려준다() throws Exception {
        authAsUser7();
        when(houseCommandService.create(eq(7L), any()))
                .thenThrow(new BusinessException(HouseErrorCode.HOUSE_GOAL_INVALID));

        mockMvc.perform(post("/api/v1/houses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"아침 루틴 하우스\", \"goalIds\": [1, 99]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("HOUSE_GOAL_INVALID"));
    }

    @Test
    void goalIds_3개는_정상_생성된다() throws Exception {
        authAsUser7();
        when(houseCommandService.create(eq(7L), any())).thenReturn(
                new HouseCreateResponse(1L, 7L, "ABCD2345", Instant.parse("2026-07-10T00:00:00Z")));

        mockMvc.perform(post("/api/v1/houses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"아침 루틴 하우스\", \"goalIds\": [1, 2, 3]}"))
                .andExpect(status().isCreated());
    }

    @Test
    void maxMembers가_상한을_넘으면_400() throws Exception {
        authAsUser7();

        mockMvc.perform(post("/api/v1/houses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"아침 루틴 하우스\", \"maxMembers\": 11, \"goalIds\": [1]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 이름이_짧으면_400() throws Exception {
        authAsUser7();

        mockMvc.perform(post("/api/v1/houses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"a\", \"goalIds\": [1]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 목록에_없는_집_커버키는_형식과_관계없이_도메인_에러를_내려준다() throws Exception {
        authAsUser7();
        when(houseCommandService.create(eq(7L), any()))
                .thenThrow(new BusinessException(HouseErrorCode.HOUSE_COVER_IMAGE_INVALID));

        mockMvc.perform(post("/api/v1/houses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "아침 루틴 하우스", "coverImageKey": "characters/cat.png",
                                 "goalIds": [1]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("HOUSE_COVER_IMAGE_INVALID"));
    }

    @Test
    void 게시되지_않은_집_커버키는_400과_에러코드를_내려준다() throws Exception {
        authAsUser7();
        when(houseCommandService.create(eq(7L), any()))
                .thenThrow(new BusinessException(HouseErrorCode.HOUSE_COVER_IMAGE_INVALID));

        mockMvc.perform(post("/api/v1/houses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "아침 루틴 하우스", "coverImageKey": "house/not-published.png",
                                 "goalIds": [1]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("HOUSE_COVER_IMAGE_INVALID"));
    }

    @Test
    void goalIds가_비어있으면_400() throws Exception {
        authAsUser7();

        mockMvc.perform(post("/api/v1/houses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"아침 루틴 하우스\", \"goalIds\": []}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void goalIds가_3개를_넘으면_400() throws Exception {
        authAsUser7();

        mockMvc.perform(post("/api/v1/houses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"아침 루틴 하우스\", \"goalIds\": [1, 2, 3, 4]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 초대코드_참여_응답_계약() throws Exception {
        authAsUser7();
        when(houseJoinService.joinByCode(7L, "ABCD2345"))
                .thenReturn(new HouseJoinResponse(12L, 1L, HouseMemberStatus.ACTIVE));

        mockMvc.perform(post("/api/v1/houses/join-by-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviteCode\": \"ABCD2345\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.membershipId").value(12))
                .andExpect(jsonPath("$.houseId").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void 초대코드가_비어있으면_400() throws Exception {
        authAsUser7();

        mockMvc.perform(post("/api/v1/houses/join-by-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviteCode\": \"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 만료된_초대코드_참여는_409와_에러코드를_내려준다() throws Exception {
        authAsUser7();
        when(houseJoinService.joinByCode(7L, "ABCD2345"))
                .thenThrow(new BusinessException(HouseErrorCode.INVITE_CODE_EXPIRED));

        mockMvc.perform(post("/api/v1/houses/join-by-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviteCode\": \"ABCD2345\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVITE_CODE_EXPIRED"));
    }

    @Test
    void 탐색_집_참여_응답_계약() throws Exception {
        authAsUser7();
        when(houseJoinService.join(7L, 1L)).thenReturn(new HouseJoinDetailResponse(
                12L, 1L, 7L, HouseMemberRole.MEMBER, HouseMemberStatus.ACTIVE,
                Instant.parse("2026-07-03T00:00:00Z")));

        mockMvc.perform(post("/api/v1/houses/1/join"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.membershipId").value(12))
                .andExpect(jsonPath("$.houseId").value(1))
                .andExpect(jsonPath("$.userId").value(7))
                .andExpect(jsonPath("$.role").value("MEMBER"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void 없는_집_참여는_404와_에러코드를_내려준다() throws Exception {
        authAsUser7();
        when(houseJoinService.join(7L, 99L))
                .thenThrow(new BusinessException(HouseErrorCode.HOUSE_NOT_FOUND));

        mockMvc.perform(post("/api/v1/houses/99/join"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HOUSE_NOT_FOUND"));
    }

    @Test
    void 집_탐색_목록_기본_경로_응답_계약() throws Exception {
        authAsUser7();
        when(houseQueryService.explore(0, 20, null)).thenReturn(new HouseListResponse(
                java.util.List.of(), 0, 20, 0L));

        mockMvc.perform(get("/api/v1/houses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void 페이지가_음수면_400() throws Exception {
        authAsUser7();

        mockMvc.perform(get("/api/v1/houses").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 페이지_크기가_0이면_400() throws Exception {
        authAsUser7();

        mockMvc.perform(get("/api/v1/houses").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 집_탐색_목록_응답_계약() throws Exception {
        authAsUser7();
        when(houseQueryService.explore(0, 20, "morning_routine")).thenReturn(new HouseListResponse(
                java.util.List.of(new HouseListResponse.HouseSummary(1L, "아침 루틴 하우스", "house/cover.png", 3, 4, 0,
                        java.util.List.of(new HouseListResponse.GoalSummary(1L, "morning_routine", "아침 루틴")))),
                0, 20, 1L));

        mockMvc.perform(get("/api/v1/houses").param("goalCode", "morning_routine"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].houseId").value(1))
                .andExpect(jsonPath("$.items[0].goals[0].code").value("morning_routine"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void 집_상세_응답_계약() throws Exception {
        authAsUser7();
        when(houseQueryService.getHouseDetail(7L, 1L)).thenReturn(new HouseDetailResponse(
                1L, "아침 루틴 하우스", "같이 아침 루틴", "house/cover.png", 4, 3, 2, 120,
                java.util.List.of(new HouseListResponse.GoalSummary(1L, "morning_routine", "아침 루틴")),
                HouseMemberRole.OWNER, "ABCD2345", Instant.parse("2026-07-10T00:00:00Z")));

        mockMvc.perform(get("/api/v1/houses/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.houseId").value(1))
                .andExpect(jsonPath("$.coverImageKey").value("house/cover.png"))
                .andExpect(jsonPath("$.growthPoints").value(120))
                .andExpect(jsonPath("$.myRole").value("OWNER"))
                .andExpect(jsonPath("$.inviteCode").value("ABCD2345"))
                .andExpect(jsonPath("$.goals[0].code").value("morning_routine"));
    }

    @Test
    void 비구성원_상세_조회는_403과_에러코드를_내려준다() throws Exception {
        authAsUser7();
        when(houseQueryService.getHouseDetail(7L, 1L))
                .thenThrow(new BusinessException(HouseErrorCode.HOUSE_NOT_MEMBER));

        mockMvc.perform(get("/api/v1/houses/1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("HOUSE_NOT_MEMBER"));
    }

    @Test
    void 구성원_목록_응답_계약() throws Exception {
        authAsUser7();
        when(houseQueryService.getMembers(7L, 1L)).thenReturn(new HouseMemberListResponse(java.util.List.of(
                new HouseMemberListResponse.MemberSummary(10L, 7L, "진형", HouseMemberRole.OWNER,
                        HouseMemberStatus.ACTIVE, Instant.parse("2026-07-03T00:00:00Z"),
                        Instant.parse("2026-07-20T05:00:00Z")))));

        mockMvc.perform(get("/api/v1/houses/1/members"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].membershipId").value(10))
                .andExpect(jsonPath("$.items[0].userId").value(7))
                .andExpect(jsonPath("$.items[0].nickname").value("진형"))
                .andExpect(jsonPath("$.items[0].role").value("OWNER"))
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.items[0].lastAccessedAt").value("2026-07-20T05:00:00Z"));
    }

    @Test
    void 비구성원_구성원_목록_조회는_403과_에러코드를_내려준다() throws Exception {
        authAsUser7();
        when(houseQueryService.getMembers(7L, 1L))
                .thenThrow(new BusinessException(HouseErrorCode.HOUSE_NOT_MEMBER));

        mockMvc.perform(get("/api/v1/houses/1/members"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("HOUSE_NOT_MEMBER"));
    }

    @Test
    void 초대코드_재발급_응답_계약() throws Exception {
        authAsUser7();
        when(houseCommandService.reissueInviteCode(7L, 1L)).thenReturn(
                new InviteCodeResponse("WXYZ6789", Instant.parse("2026-07-11T00:00:00Z")));

        mockMvc.perform(post("/api/v1/houses/1/invite-code"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inviteCode").value("WXYZ6789"))
                .andExpect(jsonPath("$.inviteExpiresAt").exists());
    }

    @Test
    void 소유자가_아닌_재발급은_403과_에러코드를_내려준다() throws Exception {
        authAsUser7();
        when(houseCommandService.reissueInviteCode(7L, 1L))
                .thenThrow(new BusinessException(HouseErrorCode.HOUSE_NOT_OWNER));

        mockMvc.perform(post("/api/v1/houses/1/invite-code"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("HOUSE_NOT_OWNER"));
    }

    @Test
    void 소유권_양도_응답_계약() throws Exception {
        authAsUser7();
        when(houseMemberCommandService.transferOwnership(7L, 1L, 12L)).thenReturn(
                new TransferOwnershipResponse(1L, 12L, 8L));

        mockMvc.perform(post("/api/v1/houses/1/transfer-ownership")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetMembershipId\": 12}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.houseId").value(1))
                .andExpect(jsonPath("$.newOwnerMembershipId").value(12))
                .andExpect(jsonPath("$.newOwnerUserId").value(8));
    }

    @Test
    void 잘못된_양도_대상은_400과_에러코드를_내려준다() throws Exception {
        authAsUser7();
        when(houseMemberCommandService.transferOwnership(7L, 1L, 99L))
                .thenThrow(new BusinessException(HouseErrorCode.HOUSE_TRANSFER_TARGET_INVALID));

        mockMvc.perform(post("/api/v1/houses/1/transfer-ownership")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetMembershipId\": 99}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("HOUSE_TRANSFER_TARGET_INVALID"));
    }

    @Test
    void 양도_대상_미지정은_400() throws Exception {
        authAsUser7();

        mockMvc.perform(post("/api/v1/houses/1/transfer-ownership")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 집_탈퇴는_204를_내려준다() throws Exception {
        authAsUser7();

        mockMvc.perform(delete("/api/v1/houses/1/members/me"))
                .andExpect(status().isNoContent());
    }

    @Test
    void 소유자의_양도_전_탈퇴는_409와_에러코드를_내려준다() throws Exception {
        authAsUser7();
        org.mockito.Mockito.doThrow(new BusinessException(HouseErrorCode.HOUSE_OWNER_MUST_TRANSFER))
                .when(houseMemberCommandService).leave(7L, 1L);

        mockMvc.perform(delete("/api/v1/houses/1/members/me"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOUSE_OWNER_MUST_TRANSFER"));
    }

    @Test
    void 구성원_강퇴는_204를_내려준다() throws Exception {
        authAsUser7();

        mockMvc.perform(delete("/api/v1/houses/1/members/12"))
                .andExpect(status().isNoContent());
        org.mockito.Mockito.verify(houseMemberCommandService).kick(7L, 1L, 12L);
    }

    @Test
    void 강퇴자의_재참여는_409와_에러코드를_내려준다() throws Exception {
        authAsUser7();
        when(houseJoinService.join(7L, 1L))
                .thenThrow(new BusinessException(HouseErrorCode.HOUSE_KICKED_MEMBER));

        mockMvc.perform(post("/api/v1/houses/1/join"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOUSE_KICKED_MEMBER"));
    }

    @Test
    void 집_설정_수정_응답_계약() throws Exception {
        authAsUser7();
        when(houseCommandService.updateSettings(eq(7L), eq(1L), any())).thenReturn(
                new HouseUpdateResponse(1L, "새 이름", "새 소개", "house/new.png", 6));

        mockMvc.perform(put("/api/v1/houses/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"새 이름\", \"maxMembers\": 6}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.houseId").value(1))
                .andExpect(jsonPath("$.name").value("새 이름"))
                .andExpect(jsonPath("$.maxMembers").value(6));
    }

    @Test
    void 인원_미만_축소는_409와_에러코드를_내려준다() throws Exception {
        authAsUser7();
        when(houseCommandService.updateSettings(eq(7L), eq(1L), any()))
                .thenThrow(new BusinessException(HouseErrorCode.HOUSE_MAX_MEMBERS_BELOW_CURRENT));

        mockMvc.perform(put("/api/v1/houses/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxMembers\": 1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOUSE_MAX_MEMBERS_BELOW_CURRENT"));
    }

    @Test
    void 설정_수정_이름이_짧으면_400() throws Exception {
        authAsUser7();

        mockMvc.perform(put("/api/v1/houses/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"a\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 초대코드_미리보기_응답_계약() throws Exception {
        authAsUser7();
        when(houseJoinService.preview("ABCD2345")).thenReturn(
                new HousePreviewResponse(1L, "아침 루틴 하우스", "house/cover.png", 3, 4, false));

        mockMvc.perform(get("/api/v1/houses/by-code/ABCD2345"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.houseId").value(1))
                .andExpect(jsonPath("$.name").value("아침 루틴 하우스"))
                .andExpect(jsonPath("$.currentMemberCount").value(3))
                .andExpect(jsonPath("$.inviteExpired").value(false));
    }

    @Test
    void 집_미리보기_응답_계약() throws Exception {
        authAsUser7();
        when(houseQueryService.getPreview(7L, 1L)).thenReturn(new HousePreviewDetailResponse(
                1L, "아침 루틴 하우스", "같이 아침 루틴 지켜요", "house/cover.png",
                4, 3, 2,
                List.of(new HouseListResponse.GoalSummary(5L, "morning_routine", "아침 루틴")),
                false, false,
                List.of(
                        new HousePreviewDetailResponse.MemberRoomSummary(12L, "진형",
                                new RoomRenderResponse(1, RoomLayoutFormat.FREE_V1, null, List.of(), List.of())),
                        new HousePreviewDetailResponse.MemberRoomSummary(13L, null, null))));

        mockMvc.perform(get("/api/v1/houses/1/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.houseId").value(1))
                .andExpect(jsonPath("$.name").value("아침 루틴 하우스"))
                .andExpect(jsonPath("$.description").value("같이 아침 루틴 지켜요"))
                .andExpect(jsonPath("$.currentMemberCount").value(3))
                .andExpect(jsonPath("$.maxMembers").value(4))
                .andExpect(jsonPath("$.level").value(2))
                .andExpect(jsonPath("$.goals[0].code").value("morning_routine"))
                .andExpect(jsonPath("$.isMember").value(false))
                .andExpect(jsonPath("$.isFull").value(false))
                // 구성원 타일 렌더용 memberRooms(#177) - 방 미생성 구성원은 room null
                .andExpect(jsonPath("$.memberRooms[0].membershipId").value(12))
                .andExpect(jsonPath("$.memberRooms[0].nickname").value("진형"))
                .andExpect(jsonPath("$.memberRooms[0].room.growthLevel").value(1))
                .andExpect(jsonPath("$.memberRooms[0].room.layoutFormat").value("FREE_V1"))
                .andExpect(jsonPath("$.memberRooms[1].room").value(nullValue()))
                // 구성원 전용 필드는 미리보기 응답에 존재하지 않아야 한다(계약 회귀 방지)
                .andExpect(jsonPath("$.myRole").doesNotExist())
                .andExpect(jsonPath("$.inviteCode").doesNotExist())
                // 방 렌더 부분집합 밖의 값(활동 정보·편집용 값)은 미리보기로 새지 않아야 한다
                .andExpect(jsonPath("$.memberRooms[0].room.streak").doesNotExist())
                .andExpect(jsonPath("$.memberRooms[0].room.layoutRevision").doesNotExist())
                .andExpect(jsonPath("$.memberRooms[0].lastAccessedAt").doesNotExist())
                .andExpect(jsonPath("$.memberRooms[0].userId").doesNotExist());
    }
}
