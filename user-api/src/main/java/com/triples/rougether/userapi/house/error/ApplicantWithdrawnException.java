package com.triples.rougether.userapi.house.error;

import com.triples.rougether.common.error.BusinessException;

// 입주 신청 승인 시 신청자가 이미 탈퇴한 경우.
// 별도 타입인 이유: 에러 응답과 함께 신청 거절(REJECTED) 전환을 커밋해야 해서
// 승인 트랜잭션의 noRollbackFor 대상으로 지정할 수 있어야 함.
public class ApplicantWithdrawnException extends BusinessException {

    public ApplicantWithdrawnException() {
        super(HouseErrorCode.HOUSE_JOIN_REQUEST_APPLICANT_WITHDRAWN);
    }
}
