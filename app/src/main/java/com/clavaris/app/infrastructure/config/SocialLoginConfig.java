package com.clavaris.app.infrastructure.config;

import com.clavaris.common.application.port.SecurityMetricsRecorder;
import com.clavaris.organization.application.usecase.setratelimitpolicyfororganization.RateLimitPolicyRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;

/**
 * ADR-0020: the OAuth2 <em>client</em> half of social login — Clavaris authenticating an end-user
 * against Google/GitHub, the opposite role from {@link
 * OrganizationAuthorizationServerConfig}/{@link PlatformAuthorizationServerConfig} (Clavaris as the
 * OAuth2/OIDC <em>server</em>). Deliberately its own chain, not folded into either of those two —
 * mixing {@code oauth2Login()} onto an already heavily-customized {@code
 * OAuth2AuthorizationServerConfigurer} chain would risk exactly the kind of filter-ordering
 * surprise {@code OrganizationAuthorizationServerConfig}'s own Javadoc already documents finding
 * once (three {@code addFilterAfter} calls silently keeping only the last-registered filter) — a
 * completely unrelated concern deserves a completely separate, independently reasoned-about chain.
 *
 * <p>{@code securityMatcher} covers Spring Security's own standard OAuth2 client paths ({@code
 * /oauth2/authorization/**} — the redirect-initiation endpoint {@code oauth2Login()} registers by
 * default, keyed by registration id; {@code /login/oauth2/code/**} — the default callback path)
 * plus {@code SocialLoginRedirectController}'s own two entry points. {@code
 * /platform/login/social/**} would otherwise fall inside {@link PlatformDashboardSecurityConfig}'s
 * broad {@code /platform/**} matcher — this chain is ordered before it (see the two classes' own
 * {@code @Order} values) so the narrower, more specific match here wins.
 *
 * <p>Shares {@link OrganizationAuthorizationServerConfig}'s own {@code securityContextRepository()}
 * bean explicitly, same "share the instance, don't rely on a default" discipline spike 0001's own
 * Appendix B/§5.3 established — not strictly load-bearing here (both {@code
 * SocialLoginAuthenticationSuccessHandler} and every other chain ultimately read/write the same
 * {@code HttpSession} attribute regardless of which repository instance wrote it), but consistent
 * with how every other chain in this codebase wires it, rather than a silent exception to that
 * rule.
 *
 * <p>PMD.AvoidDuplicateLiterals: the repeated string is "PMD.LongVariable" itself, used on four
 * different TD-PERF-009 methods below — every one of connectTimeoutSeconds/readTimeoutSeconds/
 * socialLoginUserInfoRestOperations names exactly what it is, not accidentally long, same precedent
 * IdentityUseCaseConfig's own identical class-level suppression already documents for the same
 * shape of false positive. PMD.CouplingBetweenObjects: this class wires an entire OAuth2 client
 * security chain (token exchange, two userinfo flavors, rate limiting, the new circuit breaker) —
 * same "wiring, not sprawl" rationale OrganizationAuthorizationServerConfig's own identical
 * suppression already documents for a comparably-shaped config class.
 */
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.CouplingBetweenObjects"})
@Configuration
class SocialLoginConfig {

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ SocialLoginConfig() {
    // Intentionally empty — this class holds no state, only the @Bean methods below.
  }

  // TD-PERF-009: shared by both new beans below — Google's token exchange and both providers'
  // userinfo calls all need the same connect/read ceiling, not two independently-tuned numbers.
  @SuppressWarnings("PMD.LongVariable")
  private static ClientHttpRequestFactory timeoutConfiguredRequestFactory(
      final long connectTimeoutSeconds, final long readTimeoutSeconds) {
    final JdkClientHttpRequestFactory factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .build());
    factory.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));
    return factory;
  }

  // SDE-III optimization pass, P2 point 4: shared by both beans below, same reasoning
  // timeoutConfiguredRequestFactory's own Javadoc already documents for the timeout config —
  // Google's/GitHub's own token-exchange and userinfo calls all need the same circuit-breaker
  // tuning, isolated per target host at request time (see
  // CircuitBreakerClientHttpRequestInterceptor
  // 's own Javadoc for why one shared instance, not one per bean, is still correct).
  @SuppressWarnings("PMD.LongVariable")
  @Bean
  /* package */ ClientHttpRequestInterceptor socialLoginCircuitBreakerInterceptor(
      @Value("${clavaris.resilience.social-login.failure-rate-threshold:50}")
          final float failureRateThreshold,
      @Value("${clavaris.resilience.social-login.sliding-window-size:10}")
          final int slidingWindowSize,
      @Value("${clavaris.resilience.social-login.wait-duration-in-open-state-seconds:30}")
          final long waitDurationInOpenStateSeconds,
      final SecurityMetricsRecorder metrics) {
    return new CircuitBreakerClientHttpRequestInterceptor(
        CircuitBreakerConfig.custom()
            .failureRateThreshold(failureRateThreshold)
            .slidingWindowSize(slidingWindowSize)
            .waitDurationInOpenState(Duration.ofSeconds(waitDurationInOpenStateSeconds))
            .build(),
        metrics);
  }

  // TD-PERF-009: Spring Security's own default token-response client sets no connect/read
  // timeout at all — confirmed by reading AbstractRestClientOAuth2AccessTokenResponseClient's
  // real source (7.1.1), not assumed. With Tomcat capped at 50 threads (TD-PERF-007), a hung
  // Google/GitHub token endpoint could exhaust the whole pool from this one call alone.
  // Deliberately replicates that same class's own default RestClient construction byte-for-byte
  // (same FormHttpMessageConverter/OAuth2AccessTokenResponseHttpMessageConverter/
  // OAuth2ErrorResponseErrorHandler combination) rather than building a bare RestClient — calling
  // setRestClient(...) fully replaces the default, so anything less than this exact
  // configuration would silently break real token-response parsing and OAuth2 error handling,
  // not just fix a timeout.
  @SuppressWarnings("PMD.LongVariable")
  @Bean
  /* package */ OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest>
      socialLoginAccessTokenResponseClient(
          @Value("${clavaris.oauth2.social-login.connect-timeout-seconds:5}")
              final long connectTimeoutSeconds,
          @Value("${clavaris.oauth2.social-login.read-timeout-seconds:10}")
              final long readTimeoutSeconds,
          final ClientHttpRequestInterceptor socialLoginCircuitBreakerInterceptor) {
    final RestClient restClient =
        RestClient.builder()
            .requestFactory(
                timeoutConfiguredRequestFactory(connectTimeoutSeconds, readTimeoutSeconds))
            // SDE-III optimization pass, P2 point 4 — see this class's own Javadoc above
            // (socialLoginCircuitBreakerInterceptor) for why one shared instance is correct here.
            .requestInterceptor(socialLoginCircuitBreakerInterceptor)
            .configureMessageConverters(
                messageConverters -> {
                  messageConverters.addCustomConverter(new FormHttpMessageConverter());
                  messageConverters.addCustomConverter(
                      new OAuth2AccessTokenResponseHttpMessageConverter());
                })
            .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
            .build();
    final RestClientAuthorizationCodeTokenResponseClient client =
        new RestClientAuthorizationCodeTokenResponseClient();
    client.setRestClient(restClient);
    return client;
  }

  // TD-PERF-009: same gap, same fix shape, for the userinfo call — DefaultOAuth2UserService's
  // own default RestTemplate (confirmed from its real source) also sets no timeout. Shared by
  // both userinfo call sites this codebase has: GitHubVerifiedEmailUserService's own delegate
  // (the non-OIDC userService slot) below, and socialLoginOidcUserService (the OIDC slot Google
  // actually uses) just below that. Replicates DefaultOAuth2UserService's own default
  // OAuth2ErrorResponseErrorHandler — a bare RestTemplate would silently revert userinfo error
  // handling to RestTemplate's own generic exception type instead of OAuth2AuthenticationException.
  @SuppressWarnings("PMD.LongVariable")
  @Bean
  /* package */ RestOperations socialLoginUserInfoRestOperations(
      @Value("${clavaris.oauth2.social-login.connect-timeout-seconds:5}")
          final long connectTimeoutSeconds,
      @Value("${clavaris.oauth2.social-login.read-timeout-seconds:10}")
          final long readTimeoutSeconds,
      final ClientHttpRequestInterceptor socialLoginCircuitBreakerInterceptor) {
    final RestTemplate restTemplate =
        new RestTemplate(
            timeoutConfiguredRequestFactory(connectTimeoutSeconds, readTimeoutSeconds));
    restTemplate.setErrorHandler(new OAuth2ErrorResponseErrorHandler());
    // SDE-III optimization pass, P2 point 4 — same shared interceptor as
    // socialLoginAccessTokenResponseClient's own identical addition.
    restTemplate.getInterceptors().add(socialLoginCircuitBreakerInterceptor);
    return restTemplate;
  }

  // TD-PERF-009: Google is OIDC, so its own userinfo call goes through OidcUserService, not
  // GitHubVerifiedEmailUserService's own DefaultOAuth2UserService delegate — this class's own
  // Javadoc already establishes why Google needs no GitHub-shaped customization otherwise.
  // oauth2UserService is the one delegate slot where OidcUserService actually performs the HTTP
  // call, so that's where the shared timeout-configured RestOperations plugs in.
  @SuppressWarnings("PMD.LongVariable")
  @Bean
  /* package */ OidcUserService socialLoginOidcUserService(
      final RestOperations socialLoginUserInfoRestOperations) {
    final DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();
    delegate.setRestOperations(socialLoginUserInfoRestOperations);
    final OidcUserService oidcUserService = new OidcUserService();
    oidcUserService.setOauth2UserService(delegate);
    return oidcUserService;
  }

  // CLAUDE.md §6 (code review finding): this chain was originally wired with no
  // AntiAbuseRateLimitingFilter at all — every sibling chain that handles a login-shaped
  // unauthenticated endpoint (OrganizationAuthorizationServerConfig,
  // PlatformAuthorizationServerConfig,
  // PlatformDashboardSecurityConfig) already has one; this one, covering four unauthenticated,
  // browser-facing OAuth2-client entry points, did not. IP-only, not per-account: none of these
  // four GET endpoints carry a submitted email/account identifier to key by (unlike the password
  // login form) — same "IP-only" reasoning platform-register/platform-forgot-password already
  // establish for endpoints with no pre-existing identity to key against. One shared limit/window
  // across all four paths, not four independently tuned rows: they're all one login flow's own
  // steps (initiate → provider redirect → callback → landing page), not four functionally
  // distinct endpoints with different abuse shapes.
  @SuppressWarnings({"PMD.LongVariable", "java:S107", "PMD.ExcessiveParameterList"}) // one
  // parameter per collaborating bean/tunable — same rationale as
  // OrganizationAuthorizationServerConfig's own identical suppression on its own
  // securityFilterChain method, which wires the same two rate-limiting layers.
  @Bean
  @Order(4)
  /* package */ SecurityFilterChain socialLoginSecurityFilterChain(
      final HttpSecurity http,
      final SecurityContextRepository contextRepository,
      final GitHubVerifiedEmailUserService gitHubUserService,
      final OidcUserService socialLoginOidcUserService,
      final OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest>
          socialLoginAccessTokenResponseClient,
      final SocialLoginAuthenticationSuccessHandler successHandler,
      final SocialLoginAuthenticationFailureHandler failureHandler,
      final RateLimiter rateLimiter,
      final RateLimitKeyHasher rateLimitKeyHasher,
      final RateLimitPolicyRepository rateLimitPolicies,
      @Value("${clavaris.rate-limit.social-login.per-ip-limit:30}") final int perIpLimit,
      @Value("${clavaris.rate-limit.capacity.default-requests-per-minute:600}")
          final int capacityDefaultRequestsPerMinute,
      final EmbeddingEligibilityChecker embeddingChecker) {
    http.securityMatcher(
            "/oauth2/authorization/**",
            "/login/oauth2/code/**",
            "/o/*/login/social/**",
            "/platform/login/social/**")
        .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
        .oauth2Login(
            oauth2 ->
                oauth2
                    // GitHub, the non-OIDC provider, needs its own verified-email-fetching
                    // customization. Google, the OIDC one, only needs socialLoginOidcUserService's
                    // own timeout fix, not GitHub-shaped logic — Spring's own OidcUserService
                    // already exposes email/email_verified correctly from the ID token.
                    .userInfoEndpoint(
                        userInfo ->
                            userInfo
                                .userService(gitHubUserService)
                                .oidcUserService(socialLoginOidcUserService))
                    // TD-PERF-009: without this, Spring Security silently falls back to its own
                    // default, unbounded-timeout token-response client.
                    .tokenEndpoint(
                        token ->
                            token.accessTokenResponseClient(socialLoginAccessTokenResponseClient))
                    .successHandler(successHandler)
                    .failureHandler(failureHandler))
        .securityContext(context -> context.securityContextRepository(contextRepository))
        .addFilterAfter(
            new AntiAbuseRateLimitingFilter(
                rateLimiter,
                rateLimitKeyHasher,
                List.of(
                    socialLoginIpRule(
                        "social-login-authorization:ip", "/oauth2/authorization/**", perIpLimit),
                    socialLoginIpRule(
                        "social-login-callback:ip", "/login/oauth2/code/**", perIpLimit),
                    socialLoginIpRule(
                        "social-login-landing:ip", "/o/*/login/social/**", perIpLimit),
                    socialLoginIpRule(
                        "platform-social-login-landing:ip",
                        "/platform/login/social/**",
                        perIpLimit))),
            SecurityContextHolderFilter.class)
        // Code review finding, ADR-0010 §6.2: the second rate-limiting layer — the
        // per-Organization capacity ceiling — was missing from this chain entirely, unlike every
        // other /o/{organizationId}/... endpoint in this codebase. OrganizationCapacityRateLimit
        // ingFilter's own path regex (^/o/([^/]+)/.*) already covers /o/*/login/social/** with no
        // new logic needed; it no-ops (via its own null-organizationId early-return) for this
        // chain's other three paths, which carry no organizationId path segment at all — the org
        // context for those travels via SocialLoginRedirectController's own session attribute,
        // not the URL, so this layer structurally can't reach them by design, same as it can't
        // reach any non-/o/-prefixed path on any other chain.
        .addFilterAfter(
            new OrganizationCapacityRateLimitingFilter(
                rateLimiter, rateLimitPolicies, capacityDefaultRequestsPerMinute),
            AntiAbuseRateLimitingFilter.class)
        .headers(
            headers ->
                headers.addHeaderWriter(new ContentSecurityPolicyHeaderWriter(embeddingChecker)));
    return http.build();
  }

  private static RateLimitRule socialLoginIpRule(
      final String name, final String pathPattern, final int perIpLimit) {
    return new RateLimitRule(
        name,
        HttpMethod.GET,
        pathPattern,
        RateLimitRule.always(),
        RateLimitIdentifiers::sourceIp,
        perIpLimit,
        Duration.ofMinutes(5));
  }
}
