package com.payflow.infrastructure.security;

import com.payflow.domain.model.user.User;
import com.payflow.domain.model.user.UserStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock JwtService jwtService;
    @Mock UserDetailsService userDetailsService;
    @Mock StringRedisTemplate redisTemplate;
    @Mock FilterChain filterChain;

    @InjectMocks JwtAuthenticationFilter filter;

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private UserDetails userDetails;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        userDetails = User.builder()
                .email("test@payflow.com")
                .passwordHash("hashed")
                .fullName("Test User")
                .status(UserStatus.ACTIVE)
                .build();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldPassThroughUnauthenticatedWhenNoToken() throws Exception {
        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(userDetailsService);
    }

    @Test
    void shouldAuthenticateWithValidBearerToken() throws Exception {
        request.addHeader("Authorization", "Bearer valid-token");
        when(jwtService.extractBearerToken("Bearer valid-token")).thenReturn("valid-token");
        when(jwtService.extractUsername("valid-token")).thenReturn("test@payflow.com");
        when(userDetailsService.loadUserByUsername("test@payflow.com")).thenReturn(userDetails);
        when(jwtService.validateToken("valid-token", userDetails)).thenReturn(true);
        when(jwtService.extractJti("valid-token")).thenReturn("jti-abc");
        when(redisTemplate.hasKey("denylist:jti-abc")).thenReturn(false);

        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(userDetails);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void shouldExtractTokenFromCookie() throws Exception {
        request.setCookies(new Cookie("accessToken", "cookie-token"));
        when(jwtService.extractUsername("cookie-token")).thenReturn("test@payflow.com");
        when(userDetailsService.loadUserByUsername("test@payflow.com")).thenReturn(userDetails);
        when(jwtService.validateToken("cookie-token", userDetails)).thenReturn(true);
        when(jwtService.extractJti("cookie-token")).thenReturn("jti-cookie");
        when(redisTemplate.hasKey("denylist:jti-cookie")).thenReturn(false);

        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        verify(jwtService, never()).extractBearerToken(any());
    }

    @Test
    void shouldPreferCookieOverAuthorizationHeader() throws Exception {
        request.setCookies(new Cookie("accessToken", "cookie-token"));
        request.addHeader("Authorization", "Bearer header-token");
        when(jwtService.extractUsername("cookie-token")).thenReturn("test@payflow.com");
        when(userDetailsService.loadUserByUsername("test@payflow.com")).thenReturn(userDetails);
        when(jwtService.validateToken("cookie-token", userDetails)).thenReturn(true);
        when(jwtService.extractJti("cookie-token")).thenReturn("jti-cookie");
        when(redisTemplate.hasKey("denylist:jti-cookie")).thenReturn(false);

        filter.doFilter(request, response, filterChain);

        verify(jwtService, never()).extractBearerToken(any());
        verify(jwtService, never()).extractUsername("header-token");
    }

    @Test
    void shouldReturn401AndNotCallChainWhenTokenInDenylist() throws Exception {
        request.addHeader("Authorization", "Bearer denied-token");
        when(jwtService.extractBearerToken("Bearer denied-token")).thenReturn("denied-token");
        when(jwtService.extractUsername("denied-token")).thenReturn("test@payflow.com");
        when(userDetailsService.loadUserByUsername("test@payflow.com")).thenReturn(userDetails);
        when(jwtService.validateToken("denied-token", userDetails)).thenReturn(true);
        when(jwtService.extractJti("denied-token")).thenReturn("jti-denied");
        when(redisTemplate.hasKey("denylist:jti-denied")).thenReturn(true);

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void shouldPassThroughUnauthenticatedWhenTokenFailsValidation() throws Exception {
        request.addHeader("Authorization", "Bearer bad-token");
        when(jwtService.extractBearerToken("Bearer bad-token")).thenReturn("bad-token");
        when(jwtService.extractUsername("bad-token")).thenReturn("test@payflow.com");
        when(userDetailsService.loadUserByUsername("test@payflow.com")).thenReturn(userDetails);
        when(jwtService.validateToken("bad-token", userDetails)).thenReturn(false);

        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void shouldPassThroughUnauthenticatedWhenUsernameIsNull() throws Exception {
        request.addHeader("Authorization", "Bearer malformed");
        when(jwtService.extractBearerToken("Bearer malformed")).thenReturn("malformed");
        when(jwtService.extractUsername("malformed")).thenReturn(null);

        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(userDetailsService, redisTemplate);
    }

    @Test
    void shouldSkipUserLookupWhenAlreadyAuthenticated() throws Exception {
        request.addHeader("Authorization", "Bearer valid-token");
        when(jwtService.extractBearerToken("Bearer valid-token")).thenReturn("valid-token");
        when(jwtService.extractUsername("valid-token")).thenReturn("test@payflow.com");

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));

        filter.doFilter(request, response, filterChain);

        verifyNoInteractions(userDetailsService, redisTemplate);
        verify(filterChain).doFilter(request, response);
    }
}
