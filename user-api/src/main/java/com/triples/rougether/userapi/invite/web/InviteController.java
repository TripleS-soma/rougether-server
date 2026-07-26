package com.triples.rougether.userapi.invite.web;

import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import com.triples.rougether.userapi.invite.dto.InviteRedeemRequest;
import com.triples.rougether.userapi.invite.dto.InviteRedeemResponse;
import com.triples.rougether.userapi.invite.dto.MyInviteCodeResponse;
import com.triples.rougether.userapi.invite.service.InviteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 친구 초대 - 내 코드 조회, 받은 코드 사용.
// 집 초대코드(POST /api/v1/houses/join-by-code)와는 별개다 — 이쪽은 집 참여가 아니라 재화 보상만 다룬다.
@Tag(name = "Invite", description = "친구 초대 관련 API")
@RestController
@RequestMapping("/api/v1/invites")
public class InviteController {

    private final InviteService inviteService;

    public InviteController(InviteService inviteService) {
        this.inviteService = inviteService;
    }

    @Operation(summary = "내 초대코드 조회",
            description = "내 개인 초대코드와 보상 현황을 반환합니다. 코드가 없으면 이 시점에 발급됩니다. "
                    + "친구가 이 코드를 초대코드 사용 API 에 넣으면 양쪽에 코인이 지급됩니다.")
    @GetMapping("/me")
    public MyInviteCodeResponse getMyCode(@CurrentUser AuthUser user) {
        return inviteService.getMyCode(user.id());
    }

    @Operation(summary = "초대코드 사용",
            description = "친구에게 받은 초대코드를 사용해 양쪽이 코인을 받습니다. "
                    + "한 계정은 평생 1회만 사용할 수 있고(INVITE_ALREADY_REDEEMED), 자기 코드는 쓸 수 없습니다"
                    + "(INVITE_SELF_NOT_ALLOWED). 초대자가 보상 한도를 넘긴 경우에도 사용은 성공하며 "
                    + "응답의 inviterRewarded 가 false 로 내려갑니다.")
    @PostMapping("/redeem")
    public InviteRedeemResponse redeem(@CurrentUser AuthUser user,
                                       @Valid @RequestBody InviteRedeemRequest request) {
        return inviteService.redeem(user.id(), request.code());
    }
}
