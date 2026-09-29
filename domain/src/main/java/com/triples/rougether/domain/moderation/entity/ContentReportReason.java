package com.triples.rougether.domain.moderation.entity;

// 신고 사유(#399). 스팸·욕설/괴롭힘·선정성·폭력/위협·개인정보 노출·저작권 침해·기타.
public enum ContentReportReason {
    SPAM, ABUSE, SEXUAL, VIOLENCE, PERSONAL_INFO, COPYRIGHT, OTHER
}
