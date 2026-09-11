package com.triples.rougether.userapi.furniture;

import static org.assertj.core.api.Assertions.*;
import com.triples.rougether.userapi.furniture.web.FurnitureUploadAdmissionFilter;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class FurnitureUploadAdmissionFilterTest {
    @Test void twoUploadsBlockNewParsingButNotReadsAndReleaseAfterFailure() throws Exception {
        var filter=new FurnitureUploadAdmissionFilter(2);var entered=new CountDownLatch(2);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var jobs=new java.util.ArrayList<Future<?>>();
            for(int i=0;i<2;i++)jobs.add(pool.submit(()->{
                try {filter.doFilter(new MockHttpServletRequest("POST","/api/v1/me/furniture-generations"),new MockHttpServletResponse(),(a,b)->{
                    entered.countDown();try {release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
                    throw new jakarta.servlet.ServletException("simulated downstream failure");
                });}catch(Exception ignored){}
            }));
            assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
            var response=new MockHttpServletResponse();var parsed=new AtomicInteger();
            filter.doFilter(new MockHttpServletRequest("POST","/api/v1/me/furniture-generations"),response,(a,b)->parsed.incrementAndGet());
            assertThat(response.getStatus()).isEqualTo(503);assertThat(response.getHeader("Retry-After")).isEqualTo("5");assertThat(parsed).hasValue(0);
            var encoded=new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("POST","/api/v1/me/%66urniture-generations"),encoded,(a,b)->parsed.incrementAndGet());
            assertThat(encoded.getStatus()).isEqualTo(503);assertThat(parsed).hasValue(0);
            filter.doFilter(new MockHttpServletRequest("GET","/api/v1/me/furniture-generations"),new MockHttpServletResponse(),(a,b)->parsed.incrementAndGet());
            assertThat(parsed).hasValue(1);release.countDown();for(var f:jobs)f.get(3,TimeUnit.SECONDS);
            filter.doFilter(new MockHttpServletRequest("POST","/api/v1/me/furniture-generations"),new MockHttpServletResponse(),(a,b)->parsed.incrementAndGet());
            assertThat(parsed).hasValue(2);
        } finally {release.countDown();}
    }
}
