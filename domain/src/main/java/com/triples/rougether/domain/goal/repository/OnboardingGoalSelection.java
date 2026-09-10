package com.triples.rougether.domain.goal.repository;

// 온보딩 요약에는 목표 상세(코드·이름)가 필요하지 않음.
public record OnboardingGoalSelection(Long goalId, boolean primary) {
}
