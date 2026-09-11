package com.triples.rougether.userapi.furniture.web;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 인증 필터 뒤, MVC multipart 해석 전에 접수 수를 제한함. 일반 조회에는 적용하지 않음. */
@Component
@Order(-90)
public class FurnitureUploadAdmissionFilter extends OncePerRequestFilter {
    private final Semaphore slots;
    public FurnitureUploadAdmissionFilter(@Value("${furniture.admission.max-uploads:2}") int maxUploads) {
        if (maxUploads < 1 || maxUploads > 32) throw new IllegalArgumentException("사진 동시 접수 한도 오류");
        slots = new Semaphore(maxUploads);
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod())
                || !"/api/v1/me/furniture-generations".equals(
                        org.springframework.web.util.UrlPathHelper.defaultInstance.getPathWithinApplication(request));
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if (!slots.tryAcquire()) {
            response.setStatus(503);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Retry-After", "5");
            response.setHeader("Cache-Control", "no-store");
            response.getWriter().write("{\"code\":\"FURNITURE_UPLOAD_BUSY\",\"message\":\"사진을 처리 중입니다. 잠시 후 다시 시도해 주세요.\",\"fieldErrors\":[]}");
            return;
        }
        try { chain.doFilter(request, response); }
        finally { slots.release(); }
    }
}
