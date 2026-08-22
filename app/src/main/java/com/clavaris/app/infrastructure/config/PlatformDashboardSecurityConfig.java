package com.clavaris.app.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.session.HttpSessionEventPublisher;

/**
 * ADR-0012: the {@code /platform/**} chain — {@code PlatformAccount}'s own register/login/verify/
 * forgot-password/reset-password pages (public) plus the session-authenticated dashboard (requires
 * {@code ROLE_PLATFORM_ACCOUNT}). CSRF stays at Spring Security's own default (enabled), same
 * rationale as {@code DefaultSecurityConfig}'s own identical choice — this is cookie-session-backed
 * form POSTs throughout.
 *
 * <p>{@link SessionRegistry}/{@link HttpSessionEventPublisher}: needed so {@code
 * PlatformAccountSessionRevokerBridge}'s {@code expireNow()} call (BR-ID-04's ADR-0012 equivalent)
 * actually takes effect on the next request from that session, not just marks a registry entry
 * nobody checks — {@code ConcurrentSessionFilter}, wired in below via {@code sessionConcurrency},
 * is the piece that enforces it. {@code maximumSessions(-1)}: unlimited concurrent sessions per
 * {@code PlatformAccount} — this wiring exists for revocation, not to cap how many devices one
 * account may be signed into at once.
 */
@Configuration
class PlatformDashboardSecurityConfig {

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ PlatformDashboardSecurityConfig() {
    // Intentionally empty — this class holds no state, only the @Bean methods below.
  }

  @Bean
  /* package */ SessionRegistry sessionRegistry() {
    return new SessionRegistryImpl();
  }

  // Spring Boot auto-registers any HttpSessionListener bean with the embedded servlet container —
  // confirmed live (a session survives past its establishing request and IS visible to
  // SessionRegistry#getAllSessions in PlatformAccountSessionRevokerBridgeIntegrationTest).
  @Bean
  /* package */ HttpSessionEventPublisher httpSessionEventPublisher() {
    return new HttpSessionEventPublisher();
  }

  @Bean
  @Order(4)
  /* package */ SecurityFilterChain platformDashboardSecurityFilterChain(
      final HttpSecurity http, final SessionRegistry sessionRegistry) {
    http.securityMatcher("/platform/**")
        .sessionManagement(
            session ->
                session.sessionConcurrency(
                    concurrency ->
                        // expiredUrl: confirmed live that Spring Security's own default
                        // SessionInformationExpiredStrategy (no expiredUrl set) responds 200 with
                        // a plain-text "This session has been expired..." body, not a redirect —
                        // functionally correct (the old session really is rejected) but a poor
                        // user experience. Sending the browser back to a real page it can act on
                        // is a one-line fix once the gap is known.
                        concurrency
                            .maximumSessions(-1)
                            .sessionRegistry(sessionRegistry)
                            .expiredUrl("/platform/login")))
        .authorizeHttpRequests(
            authorize ->
                authorize
                    .requestMatchers(
                        "/platform/register",
                        "/platform/register/pending-verification",
                        "/platform/login",
                        "/platform/verify-email",
                        "/platform/forgot-password",
                        "/platform/forgot-password/pending",
                        "/platform/reset-password",
                        "/platform/reset-password/success")
                    .permitAll()
                    .anyRequest()
                    // Security finding (SDE-III review, 2026-08-22): this was `.authenticated()`,
                    // which only checks "is there any SecurityContext" — since app-wide there is
                    // exactly one SecurityContextRepository bean
                    // (OrganizationAuthorizationServerConfig.securityContextRepository()), shared
                    // by both this chain's SpringSecurityPlatformAuthenticatedSessionEstablisher
                    // and the tenant tier's SpringSecurityAuthenticatedSessionEstablisher, a plain
                    // tenant Account's session satisfied it too. Confirmed live before this fix: a
                    // tenant Account, logged in only via /o/{organizationId}/login, could GET (and
                    // POST to) /platform/dashboard and successfully create an Organization owned by
                    // its own AccountId masquerading as a PlatformAccountId. hasAuthority, not
                    // authenticated(), is what actually enforces "this session belongs to a
                    // PlatformAccount" — the authority SpringSecurityPlatformAuthenticatedSession
                    // Establisher grants and no tenant session ever carries.
                    .hasAuthority("ROLE_PLATFORM_ACCOUNT"))
        .exceptionHandling(
            exceptions ->
                exceptions
                    .authenticationEntryPoint(new PlatformLoginRedirectEntryPoint())
                    // hasAuthority (unlike authenticated()) rejects an authenticated-but-wrong-tier
                    // session via AccessDeniedException, not AuthenticationException — that path
                    // only reaches authenticationEntryPoint above for the anonymous case. Without
                    // this, a tenant session hitting /platform/dashboard would now get Spring
                    // Security's default whitelabel 403 instead of a clean redirect; from this
                    // session's own point of view it is simply not logged in to this tier, so it
                    // gets sent to the same place an anonymous visitor would.
                    .accessDeniedHandler(
                        (request, response, _) ->
                            response.sendRedirect(request.getContextPath() + "/platform/login")));
    return http.build();
  }
}
