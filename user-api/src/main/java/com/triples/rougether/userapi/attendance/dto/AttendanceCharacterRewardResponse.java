package com.triples.rougether.userapi.attendance.dto;

public record AttendanceCharacterRewardResponse(
        boolean grantedNow,
        AttendanceEventStatusResponse status) { }
