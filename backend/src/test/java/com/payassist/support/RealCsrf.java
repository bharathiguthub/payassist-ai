package com.payassist.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * CSRF for integration tests, done the way the browser does it: read the {@code XSRF-TOKEN} cookie the
 * server issues and echo it in the {@code X-XSRF-TOKEN} header (double-submit).
 *
 * <p>Do <b>not</b> use {@code SecurityMockMvcRequestPostProcessors.csrf()} in {@code *IT} classes: it
 * permanently replaces the {@code CsrfTokenRepository} of the (shared, cached) application context with a
 * test repository that never issues the cookie, which breaks every later test that relies on it.
 * It is fine in {@code @WebMvcTest} slices, whose contexts are not shared with integration tests.
 */
public final class RealCsrf {

    public static final String COOKIE = "XSRF-TOKEN";
    public static final String HEADER = "X-XSRF-TOKEN";

    private RealCsrf() {
    }

    /** A fresh token from a public endpoint (cookie-based tokens are not bound to a user or session). */
    public static RequestPostProcessor token(MockMvc mvc) throws Exception {
        Cookie cookie = mvc.perform(get("/actuator/health")).andReturn().getResponse().getCookie(COOKIE);
        if (cookie == null) {
            throw new IllegalStateException("The server did not issue an " + COOKIE + " cookie; has a test replaced "
                    + "the CsrfTokenRepository (e.g. with SecurityMockMvcRequestPostProcessors.csrf())?");
        }
        return request -> {
            List<Cookie> cookies = new ArrayList<>(request.getCookies() != null
                    ? Arrays.asList(request.getCookies()) : List.of());
            cookies.add(cookie);
            request.setCookies(cookies.toArray(Cookie[]::new));
            request.addHeader(HEADER, cookie.getValue());
            return request;
        };
    }
}
