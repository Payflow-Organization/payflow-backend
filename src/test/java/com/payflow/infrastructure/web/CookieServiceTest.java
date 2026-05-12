package com.payflow.infrastructure.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CookieServiceTest {

    private CookieService cookieService;

    @BeforeEach
    void setUp() {
        cookieService = new CookieService();
        ReflectionTestUtils.setField(cookieService, "accessTokenExpirationMs", 900_000L);
        ReflectionTestUtils.setField(cookieService, "refreshTokenExpirationMs", 604_800_000L);
    }

    @Test
    void shouldSetBothCookiesWithCorrectAttributes() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        cookieService.setTokenCookies(response, "acc-tok", "ref-tok");

        List<String> cookies = response.getHeaders(HttpHeaders.SET_COOKIE);
        assertThat(cookies).hasSize(2);

        String accessCookie = cookies.get(0);
        assertThat(accessCookie).contains("accessToken=acc-tok")
                .containsIgnoringCase("HttpOnly")
                .containsIgnoringCase("Secure")
                .containsIgnoringCase("SameSite=Strict")
                .contains("Path=/")
                .contains("Max-Age=900");

        String refreshCookie = cookies.get(1);
        assertThat(refreshCookie).contains("refreshToken=ref-tok")
                .contains("Max-Age=604800");
    }

    @Test
    void shouldClearBothCookiesBySettingMaxAgeZero() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        cookieService.removeTokenCookies(response);

        List<String> cookies = response.getHeaders(HttpHeaders.SET_COOKIE);
        assertThat(cookies).hasSize(2);

        String accessCookie = cookies.get(0);
        assertThat(accessCookie).contains("accessToken=")
                .contains("Max-Age=0");

        String refreshCookie = cookies.get(1);
        assertThat(refreshCookie).contains("refreshToken=")
                .contains("Max-Age=0");
    }

    @Test
    void shouldPreserveSecurityAttributesOnRemove() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        cookieService.removeTokenCookies(response);

        for (String cookie : response.getHeaders(HttpHeaders.SET_COOKIE)) {
            assertThat(cookie).containsIgnoringCase("HttpOnly");
            assertThat(cookie).containsIgnoringCase("Secure");
            assertThat(cookie).containsIgnoringCase("SameSite=Strict");
            assertThat(cookie).contains("Path=/");
        }
    }
}
