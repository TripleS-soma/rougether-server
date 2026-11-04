package com.triples.rougether.userapi.house;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseDisplayMode;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.house.dto.HouseUpdateRequest;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import com.triples.rougether.userapi.house.service.HouseCommandService;
import com.triples.rougether.userapi.house.service.HouseJoinService;
import com.triples.rougether.userapi.house.service.HouseQueryService;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import io.swagger.v3.core.util.Json31;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class HouseDisplayModeIntegrationTest {
    @Autowired HouseCommandService commands;
    @Autowired HouseQueryService queries;
    @Autowired HouseJoinService joins;
    @Autowired HouseRepository houses;
    @Autowired HouseMemberRepository members;
    @Autowired UserRepository users;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @Test
    void 기본값은_리디자인이고_DB_기본값도_같다() {
        User owner = users.save(User.signUp("display-default@test.dev"));
        House house = create(owner, "MODE0001");
        assertThat(house.getDisplayMode()).isEqualTo(HouseDisplayMode.DIORAMA);
        house.changeDisplayMode(HouseDisplayMode.CLASSIC);
        em.flush();
        jdbc.update("UPDATE house SET display_mode = DEFAULT WHERE id = ?", house.getId());
        em.clear();
        assertThat(houses.findById(house.getId()).orElseThrow().getDisplayMode())
                .isEqualTo(HouseDisplayMode.DIORAMA);
    }

    @Test
    void 방장이_저장한_모드는_재조회와_모든_집_미리보기에서_공유되고_생략하면_유지된다() {
        User owner = users.save(User.signUp("display-owner@test.dev"));
        User friend = users.save(User.signUp("display-friend@test.dev"));
        House house = create(owner, "MODE0002");
        members.save(HouseMember.create(house, friend, HouseMemberRole.MEMBER));
        house.increaseMemberCount();
        var saved = commands.updateSettings(owner.getId(), house.getId(), request(HouseDisplayMode.CLASSIC));
        assertThat(saved.displayMode()).isEqualTo(HouseDisplayMode.CLASSIC);
        em.flush();
        em.clear();
        assertThat(queries.getHouseDetail(friend.getId(), house.getId()).displayMode()).isEqualTo(HouseDisplayMode.CLASSIC);
        assertThat(queries.getMyHouses(friend.getId()).items().getFirst().displayMode()).isEqualTo(HouseDisplayMode.CLASSIC);
        assertThat(queries.getPreview(friend.getId(), house.getId()).displayMode()).isEqualTo(HouseDisplayMode.CLASSIC);
        assertThat(joins.preview("MODE0002").displayMode()).isEqualTo(HouseDisplayMode.CLASSIC);
        assertThat(queries.explore(friend.getId(), 0, 50, null, false).items().stream()
                .filter(item -> item.houseId().equals(house.getId())).findFirst().orElseThrow().displayMode())
                .isEqualTo(HouseDisplayMode.CLASSIC);
        assertThat(commands.updateSettings(owner.getId(), house.getId(), request(null)).displayMode())
                .isEqualTo(HouseDisplayMode.CLASSIC);
        assertThat(commands.updateSettings(owner.getId(), house.getId(), request(HouseDisplayMode.DIORAMA)).displayMode())
                .isEqualTo(HouseDisplayMode.DIORAMA);
    }

    @Test
    void 일반_구성원은_직접_API를_호출해도_모드를_바꿀_수_없다() {
        User owner = users.save(User.signUp("display-guard-owner@test.dev"));
        User friend = users.save(User.signUp("display-guard-friend@test.dev"));
        House house = create(owner, "MODE0003");
        members.save(HouseMember.create(house, friend, HouseMemberRole.MEMBER));
        assertThatThrownBy(() -> commands.updateSettings(friend.getId(), house.getId(), request(HouseDisplayMode.CLASSIC)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_NOT_OWNER));
        assertThat(house.getDisplayMode()).isEqualTo(HouseDisplayMode.DIORAMA);
    }

    @Test
    void API_계약에_두_모드와_모든_집_응답의_공통_설정이_포함된다() throws Exception {
        String document = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var schemas = Json31.mapper().readTree(document).path("components").path("schemas");
        for (String name : new String[]{"HouseUpdateRequest", "HouseUpdateResponse", "HouseDetailResponse",
                "HouseSummary", "HousePreviewResponse", "HousePreviewDetailResponse", "MyHouseSummary", "MyJoinRequestSummary"}) {
            var mode = schemas.path(name).path("properties").path("displayMode");
            assertThat(mode.path("enum").toString()).as(name).isEqualTo("[\"DIORAMA\",\"CLASSIC\"]");
        }
        // 앱 타입 생성에 사용할 때만 공개 OpenAPI 산출물을 기록한다.
        String output = System.getenv("ROUGETHER_OPENAPI_OUTPUT");
        if (output != null) Files.writeString(Path.of(output), document);
    }

    private House create(User owner, String code) {
        House house = houses.save(House.create(owner, "우리 집", null, null, 4, code,
                Instant.now().plus(7, ChronoUnit.DAYS)));
        members.save(HouseMember.create(house, owner, HouseMemberRole.OWNER));
        return house;
    }

    private HouseUpdateRequest request(HouseDisplayMode mode) {
        return new HouseUpdateRequest(null, null, null, null, null, mode);
    }
}
