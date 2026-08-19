package com.triples.rougether.adminapi.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

@Configuration
public class AdminSessionConfig {

    @Bean
    CookieSerializer adminSessionCookieSerializer(
            @Value("${server.servlet.session.cookie.secure:false}") boolean secure,
            @Value("${server.servlet.session.cookie.http-only:true}") boolean httpOnly,
            @Value("${server.servlet.session.cookie.same-site:strict}") String sameSite) {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName("JSESSIONID");
        serializer.setCookiePath("/");
        serializer.setUseHttpOnlyCookie(httpOnly);
        serializer.setUseSecureCookie(secure);
        serializer.setSameSite(sameSite);
        return serializer;
    }
}
