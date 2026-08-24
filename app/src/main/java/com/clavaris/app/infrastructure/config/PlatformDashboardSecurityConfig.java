package com.clavaris.app.infrastructure.config;

import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.security.SpringSessionBackedSessionRegistry;

/**
 * ADR-0012: the {@code /platform/**} chain — {@code PlatformAccount}'s own register/login/verify/
 * forgot-password/reset-password pages (public) plus the session-authenticated dashboard (requires
 * {@code ROLE_PLATFORM_ACCOUNT}). CSRF stays at Spring Security's own default (enabled), same
 * rationale as {@code DefaultSecurityConfig}'s own identical choice — this is cookie-session-backed
 * form POSTs throughout.
 *
 * <p>{@link SessionRegistry}: needed so {@code PlatformAccountSessionRevokerBridge}'s {@code
 * expireNow()} call (BR-ID-04's ADR-0012 equivalent) actually takes effect on the next request from
 * that session, not just marks a registry entry nobody checks — {@code ConcurrentSessionFilter},
 * wired in below via {@code sessionConcurrency}, is the piece that enforces it. {@code
 * maximumSessions(-1)}: unlimited concurrent sessions per {@code PlatformAccount} — this wiring
 * exists for revocation, not to cap how many devices one account may be signed into at once.
 *
 * <p>TD-ARCH-002 (closed): {@link SpringSessionBackedSessionRegistry}, not the plain {@code
 * SessionRegistryImpl} this class used before — that implementation is a local, in-JVM map
 * populated via {@code HttpSessionEventPublisher}'s servlet-container lifecycle events, so a
 * revocation issued against instance B would never see a session actually held by instance A once
 * more than one instance runs. Spring Session's own registry queries the same Redis-backed,
 * cross-instance-visible store every request's session already lives in (see {@code
 * application.yml}'s {@code spring.session.*}) — {@code HttpSessionEventPublisher} is no longer
 * needed alongside it (confirmed against Spring Security's own reference docs: Spring Session
 * already keeps this registry's view current, the publisher's job under the old registry).
 */
@Configuration
class PlatformDashboardSecurityConfig {

  // Every authenticated-but-rejected path on this chain (concurrent-session expiry, permitAll
  // list, the wrong-tier accessDeniedHandler) sends the browser to the same one page — a single
  // constant, not three independent literals that could silently drift apart.
  @SuppressWarnings("PMD.LongVariable")
  private static final String PLATFORM_LOGIN_PATH = "/platform/login";

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ PlatformDashboardSecurityConfig() {
    // Intentionally empty — this class holds no state, only the @Bean methods below.
  }

  // TD-ARCH-002 (closed): backed by the same Redis-indexed repository every real HttpSession now
  // lives in (application.yml's spring.session.redis.repository-type: indexed). Its
  // findByIndexNameAndIndexValue(PRINCIPAL_NAME_INDEX_NAME, ...) is what
  // PlatformAccountSessionRevokerBridge's getAllSessions(principalName, false) call resolves to
  // under the hood, querying Redis live rather than a local in-JVM map — a revocation issued on
  // one instance correctly sees (and expires) a session established on another. No
  // HttpSessionEventPublisher bean needed alongside this — that publisher only exists to keep the
  // old SessionRegistryImpl's local map in sync with real container events; this registry has no
  // local map to keep in sync.
  @Bean
  /* package */ SessionRegistry sessionRegistry(
      final FindByIndexNameSessionRepository<? extends Session> sessionRepository) {
    return new SpringSessionBackedSessionRegistry<>(sessionRepository);
  }

  // Descriptive @Value property names over PMD's default LongVariable threshold, same convention
  // already used throughout this codebase (e.g. PlatformAuthorizationServerConfig's own
  // tokenIssuanceLogger param) — a shortened identifier would only make this rule list harder to
  // read.
  @SuppressWarnings("PMD.LongVariable")
  @Bean
  @Order(4)
  /* package */ SecurityFilterChain platformDashboardSecurityFilterChain(
      final HttpSecurity http,
      final SessionRegistry sessionRegistry,
      final RateLimiter rateLimiter,
      @Value("${clavaris.rate-limit.login.per-account-limit:10}") final int loginPerAccountLimit,
      @Value("${clavaris.rate-limit.login.per-ip-limit:30}") final int loginPerIpLimit,
      @Value("${clavaris.rate-limit.platform-register.per-ip-limit:10}")
          final int registerPerIpLimit,
      @Value("${clavaris.rate-limit.platform-forgot-password.per-ip-limit:10}")
          final int forgotPasswordPerIpLimit,
      @Value("${clavaris.rate-limit.platform-create-organization.per-account-limit:10}")
          final int createOrganizationPerAccountLimit) {
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
                            .expiredUrl(PLATFORM_LOGIN_PATH)))
        .authorizeHttpRequests(
            authorize ->
                authorize
                    .requestMatchers(
                        "/platform/register",
                        "/platform/register/pending-verification",
                        PLATFORM_LOGIN_PATH,
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
                            response.sendRedirect(request.getContextPath() + PLATFORM_LOGIN_PATH)))
        // ADR-0010 §6.1/BR-ID-06 widened (TD-SEC-001, SDE-III review, 2026-08-22): ADR-0010 itself
        // predates ADR-0012, so its "login (/oauth2/token, password login)" wording never
        // literally named this tier's own equivalents — the same anti-abuse reasoning obviously
        // applies to them, and TD-SEC-001 already named exactly these endpoints as the widened
        // gap this closes. No capacity-layer filter here — §6.2 is per-Organization, and none of
        // these endpoints act within an already-created Organization's own scope.
        .addFilterAfter(
            new AntiAbuseRateLimitingFilter(
                rateLimiter,
                List.of(
                    new RateLimitRule(
                        "platform-login:account",
                        HttpMethod.POST,
                        PLATFORM_LOGIN_PATH,
                        RateLimitRule.always(),
                        RateLimitIdentifiers::emailFormField,
                        loginPerAccountLimit,
                        Duration.ofMinutes(5)),
                    new RateLimitRule(
                        "platform-login:ip",
                        HttpMethod.POST,
                        PLATFORM_LOGIN_PATH,
                        RateLimitRule.always(),
                        RateLimitIdentifiers::sourceIp,
                        loginPerIpLimit,
                        Duration.ofMinutes(5)),
                    // IP-only, not per-account: registration has no existing account to key by,
                    // and forgot-password is anti-enumeration by design (BR-ID-05) — keying it by
                    // the submitted email would itself leak "this address is/isn't registered"
                    // through which counter got consumed.
                    new RateLimitRule(
                        "platform-register:ip",
                        HttpMethod.POST,
                        "/platform/register",
                        RateLimitRule.always(),
                        RateLimitIdentifiers::sourceIp,
                        registerPerIpLimit,
                        Duration.ofMinutes(5)),
                    new RateLimitRule(
                        "platform-forgot-password:ip",
                        HttpMethod.POST,
                        "/platform/forgot-password",
                        RateLimitRule.always(),
                        RateLimitIdentifiers::sourceIp,
                        forgotPasswordPerIpLimit,
                        Duration.ofMinutes(5)),
                    // Self-service Organization creation (ADR-0012) — each call provisions a real
                    // signing key, so this is a real-resource-consumption limit, not just an
                    // anti-guessing one. Keyed by the authenticated PlatformAccount itself, not
                    // IP: hasAuthority(ROLE_PLATFORM_ACCOUNT) above already guarantees a real
                    // session by the time this rule's own request reaches it.
                    new RateLimitRule(
                        "platform-create-organization:account",
                        HttpMethod.POST,
                        "/platform/dashboard",
                        RateLimitRule.always(),
                        RateLimitIdentifiers::authenticatedPlatformAccountId,
                        createOrganizationPerAccountLimit,
                        Duration.ofMinutes(5)))),
            SecurityContextHolderFilter.class);
    return http.build();
  }
}
