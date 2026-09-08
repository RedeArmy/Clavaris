package com.clavaris.app.infrastructure.config;

import com.clavaris.common.application.port.SecurityMetricsRecorder;
import com.clavaris.common.infrastructure.adapter.out.resilience.CircuitBreakerMetricsBinder;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestOperations;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * ADR-0020 Decision 1: GitHub's base {@code GET /user} response (what Spring's own {@link
 * DefaultOAuth2UserService} fetches) has an {@code email} field, but that field carries no
 * "verified" indicator at all — it can be null, and even when present there is no way to tell from
 * that response alone whether it was ever confirmed. {@code
 * AuthenticateWithSocialProviderService}'s entire linking-decision safety (BR-ID-09) depends on the
 * provider's email claim being genuinely trustworthy, so trusting that field directly would be
 * exactly the kind of shortcut this codebase's own security posture (§6) refuses to take. The only
 * way to actually learn this is a second call to {@code GET /user/emails} (requires the {@code
 * user:email} scope, granted in {@code application.yml}'s own {@code github.scope}), which returns
 * each address with its own {@code primary}/{@code verified} flags — this class makes that call and
 * attaches the result as a synthetic {@value #VERIFIED_EMAIL_ATTRIBUTE} attribute {@code
 * SocialLoginAuthenticationSuccessHandler} reads back out, rather than ever trusting the base
 * response's own {@code email} field.
 *
 * <p>Only special-cases the {@code github} registration id, delegating everything else straight to
 * {@link DefaultOAuth2UserService} unmodified — ADR-0020 Decision 5 names Microsoft as a future,
 * additive provider; a third non-OIDC registration sharing this same {@code userService()} slot one
 * day should fall through here untouched, not be silently mishandled by GitHub-specific logic.
 *
 * <p>TD-PERF-009 (closed): {@link #delegate}'s own default {@code RestOperations} (a bare {@code
 * RestTemplate}) set no connect/read timeout — {@code SocialLoginConfig}'s own {@code
 * socialLoginUserInfoRestOperations} bean now supplies one instead, injected here via constructor
 * rather than left at {@code DefaultOAuth2UserService}'s own internal default.
 *
 * <p><b>SDE-III optimization pass, P2 point 4:</b> the {@code GET /user/emails} call this class
 * owns directly (not {@link #delegate}'s own base {@code /user} fetch — see {@code
 * SocialLoginConfig}'s own circuit breaker for that one) now runs through a {@link CircuitBreaker},
 * same "fail fast on a dependency already known to be down" reasoning {@code ResendHttpClient}'s
 * own identical addition documents — a GitHub outage today means every affected login still pays
 * this class's own {@link #REQUEST_TIMEOUT} (10s) before failing.
 */
// PMD.LongVariable: every flagged name here (VERIFIED_EMAIL_ATTRIBUTE, GITHUB_REGISTRATION_ID,
// DEFAULT_GITHUB_EMAILS_ENDPOINT) names exactly what it is — see this class's own Javadoc.
// PMD.LawOfDemeter: userRequest.getClientRegistration()/getAccessToken() are the standard
// OAuth2UserRequest API shape — there is no other way to reach either, same rationale
// AntiAbuseRateLimitingFilter's own response.getWriter() suppression already documents.
// PMD.OnlyOneReturn: loadUser (delegate-vs-GitHub-specific) and findPrimaryVerifiedEmail
// (found-vs-none) each have two real, distinct outcomes — same "each outcome needs its own exit"
// rationale as SetRateLimitPolicyController's own identical suppression.
// @Service, not @Component: SonarCloud flagged the mismatch between this class's own name
// (ending in "Service", mirroring the Spring Security OAuth2UserService interface it implements —
// same convention as Spring's own DefaultOAuth2UserService) and a bare @Component annotation.
// Every other infrastructure/config bean in this package uses @Component (none of their names end
// in "Service"), so this is a one-off, deliberately not renamed away from matching the framework
// interface it implements just to keep @Component consistent with its siblings.
@SuppressWarnings({"PMD.LongVariable", "PMD.LawOfDemeter", "PMD.OnlyOneReturn"})
@Service
class GitHubVerifiedEmailUserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

  /**
   * Package-visible so {@code SocialLoginAuthenticationSuccessHandler} reads back the exact same
   * key — same "define once, reference from the one place that reads it" convention as {@code
   * SocialLoginRedirectController.ORGANIZATION_ID_SESSION_ATTRIBUTE}. The value is the verified
   * primary email address as a plain {@code String}, or absent entirely if GitHub reports none.
   */
  /* package */ static final String VERIFIED_EMAIL_ATTRIBUTE = "clavaris_github_verified_email";

  private static final String GITHUB_REGISTRATION_ID = "github";
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
  private static final int SUCCESS_STATUS_CODE = 200;

  // Extracted purely to remove this literal's duplication (PMD.AvoidDuplicateLiterals) across
  // every OAuth2Error this class raises for an unavailable /user/emails call.
  private static final String EMAILS_UNAVAILABLE_ERROR_CODE = "github_emails_unavailable";

  // Efficiency (code review finding): this class runs during Spring Security's own
  // OAuth2UserService phase, strictly before AuthenticateWithSocialProviderService ever gets a
  // chance to decide whether this is a returning login (whose own branch never reads the fetched
  // email at all) or a new signup/pending-link (which does) — there is no way to skip the /user/
  // emails call outright at this layer without restructuring the whole login pipeline. A short,
  // bounded, in-process cache (not Redis — this is a per-instance latency optimization, not state
  // that needs cross-instance consistency the way rate limits or sessions do) absorbs the common
  // case of the same person logging in more than once within a few minutes; a cold/expired entry
  // still makes the real call, same as before.
  //
  // TD-PERF-016: Caffeine, not a hand-rolled Collections.synchronizedMap(LinkedHashMap) with an
  // access-order-eviction override — same "less code, not more" migration TD-PERF-006 already made
  // for CachingRateLimitPolicyRepository/CachingClientDomainConfigRepository. The prior version's
  // single coarse-grained lock (synchronizedMap wraps every read AND write in one mutex) serialized
  // every concurrent GitHub login through it; Caffeine's own striped internals don't. maximumSize
  // and expireAfterWrite together replace both the LRU-eviction override and the manual
  // CachedVerifiedEmail/expiresAt bookkeeping the old version needed to implement TTL by hand.
  private static final int MAX_CACHED_VERIFIED_EMAILS = 1000;
  private static final Duration VERIFIED_EMAIL_CACHE_TTL = Duration.ofMinutes(5);

  private final DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final URI emailsEndpoint;
  private final CircuitBreaker circuitBreaker;
  private final Cache<String, String> verifiedEmailCache =
      Caffeine.newBuilder()
          .maximumSize(MAX_CACHED_VERIFIED_EMAILS)
          .expireAfterWrite(VERIFIED_EMAIL_CACHE_TTL)
          .build();

  // @Autowired required now that a second constructor exists (below) — same fix ResendMailSender's
  // own identical situation already established: without it, Spring has no way to pick between the
  // two candidates and falls back to looking for a no-arg constructor that doesn't exist, a real
  // ApplicationContext startup failure caught live, not by inspection.
  //
  // emailsUri is a Clavaris-owned property, not part of Spring Boot's own OAuth2ClientProperties
  // provider schema (that only covers the standard OAuth2/OIDC endpoints — authorization/token/
  // user-info — this is a GitHub-API-specific extra call with no OAuth2-standard equivalent).
  // Phase 6 (SocialLoginIntegrationTest) found live that a hardcoded constant here made this call
  // unreachable from any test: it always hit the real api.github.com, which correctly 401s a stub
  // access token — a real, previously-undetected gap, not a hypothetical one. Configurable the same
  // way the OAuth2 provider's own authorization-uri/token-uri/user-info-uri already are, so a test
  // can point it at a local stub exactly like it already does for those.
  // SDE-III optimization pass, P2 point 4: the three new @Value params tune this dependency's own
  // CircuitBreaker — same defaults/rationale as ResendMailSender's own identical addition.
  @SuppressWarnings("java:S107") // one parameter per collaborating value — same rationale as
  // every other multi-collaborator constructor in this codebase.
  @Autowired
  /* package */ GitHubVerifiedEmailUserService(
      final ObjectMapper objectMapper,
      @Value("${clavaris.oauth2.github.emails-uri:https://api.github.com/user/emails}")
          final String emailsUri,
      final RestOperations userInfoRestOperations,
      @Value("${clavaris.resilience.github.failure-rate-threshold:50}")
          final float failureRateThreshold,
      @Value("${clavaris.resilience.github.sliding-window-size:10}") final int slidingWindowSize,
      @Value("${clavaris.resilience.github.wait-duration-in-open-state-seconds:30}")
          final long waitDurationInOpenStateSeconds,
      final SecurityMetricsRecorder metrics) {
    this(
        HttpClient.newHttpClient(),
        objectMapper,
        URI.create(emailsUri),
        userInfoRestOperations,
        buildCircuitBreaker(
            failureRateThreshold, slidingWindowSize, waitDurationInOpenStateSeconds, metrics));
  }

  // Test-only, same rationale as ResendMailSender's own identical second constructor — lets a test
  // inject a fully-controlled HttpClient/endpoint without a real network call. Same parameter
  // shape as before this pass — every existing GitHubVerifiedEmailUserServiceTest call site must
  // keep working unmodified. Delegates to the full constructor below with a bare-default
  // CircuitBreaker (metrics binding only matters in production).
  /* package */ GitHubVerifiedEmailUserService(
      final HttpClient httpClient,
      final ObjectMapper objectMapper,
      final URI emailsEndpoint,
      final RestOperations userInfoRestOperations) {
    this(
        httpClient,
        objectMapper,
        emailsEndpoint,
        userInfoRestOperations,
        CircuitBreaker.ofDefaults("github-emails"));
  }

  // Package-private, not private: GitHubVerifiedEmailUserServiceCircuitBreakerTest constructs
  // this directly with a small, test-friendly CircuitBreaker window — same rationale
  // ResendHttpClient's own package-private (not private) constructor already establishes.
  @SuppressWarnings("java:S107")
  /* package */ GitHubVerifiedEmailUserService(
      final HttpClient httpClient,
      final ObjectMapper objectMapper,
      final URI emailsEndpoint,
      final RestOperations userInfoRestOperations,
      final CircuitBreaker circuitBreaker) {
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;
    this.emailsEndpoint = emailsEndpoint;
    this.circuitBreaker = circuitBreaker;
    // TD-PERF-009: see this class's own Javadoc — delegate's default RestOperations has no
    // timeout at all otherwise.
    this.delegate.setRestOperations(userInfoRestOperations);
  }

  private static CircuitBreaker buildCircuitBreaker(
      final float failureRateThreshold,
      final int slidingWindowSize,
      final long waitDurationInOpenStateSeconds,
      final SecurityMetricsRecorder metrics) {
    final CircuitBreaker circuitBreaker =
        CircuitBreaker.of(
            "github-emails",
            CircuitBreakerConfig.custom()
                .failureRateThreshold(failureRateThreshold)
                .slidingWindowSize(slidingWindowSize)
                .waitDurationInOpenState(Duration.ofSeconds(waitDurationInOpenStateSeconds))
                .build());
    CircuitBreakerMetricsBinder.bind(circuitBreaker, metrics);
    return circuitBreaker;
  }

  @Override
  public OAuth2User loadUser(final OAuth2UserRequest userRequest) {
    final OAuth2User user = delegate.loadUser(userRequest);
    if (!GITHUB_REGISTRATION_ID.equals(userRequest.getClientRegistration().getRegistrationId())) {
      return user;
    }

    final Map<String, Object> attributes = new HashMap<>(user.getAttributes());
    // GitHub's own stable numeric user id (this registration's userNameAttributeName, see the
    // ClientRegistration wiring) — already present on the delegate's own base /user response, no
    // extra call needed to read it, and stable across logins the way an access token never is.
    final String githubUserId = String.valueOf(user.getAttributes().get("id"));
    final String verifiedEmail =
        resolveVerifiedEmail(githubUserId, userRequest.getAccessToken().getTokenValue());
    if (verifiedEmail != null) {
      attributes.put(VERIFIED_EMAIL_ATTRIBUTE, verifiedEmail);
    }

    return new DefaultOAuth2User(user.getAuthorities(), attributes, "id");
  }

  private String resolveVerifiedEmail(final String githubUserId, final String accessToken) {
    final String cached = verifiedEmailCache.getIfPresent(githubUserId);
    if (cached != null) {
      return cached;
    }
    final String verifiedEmail = fetchPrimaryVerifiedEmail(accessToken);
    // Code review finding: only cache a real, positive result. Caching a null (no primary
    // verified email found) for the full TTL would incorrectly reject a retry from a user who
    // verifies their email on GitHub's own side and signs in again within that window — the
    // negative result has no reason to be trusted for as long as a positive one does, and the
    // whole point of this cache (absorbing a *returning* user's repeat login) only ever applies
    // to the positive case in the first place.
    if (verifiedEmail != null) {
      verifiedEmailCache.put(githubUserId, verifiedEmail);
    }
    return verifiedEmail;
  }

  // Split into two smaller methods purely to bring cyclomatic complexity back under this
  // codebase's own threshold — sendEmailsRequest owns the HTTP call and its error handling,
  // findPrimaryVerifiedEmail owns interpreting the (already-successful) response body. Not two
  // independently reusable concerns, just one method that had grown too many branches for one body.
  private String fetchPrimaryVerifiedEmail(final String accessToken) {
    final String responseBody = sendEmailsRequest(accessToken);
    final JsonNode emails;
    try {
      emails = objectMapper.readTree(responseBody);
    } catch (final JacksonException e) {
      // Code review finding: Jackson 3.x's JacksonException is unchecked, and Spring Security's
      // own ProviderManager/AbstractAuthenticationProcessingFilter only catch
      // AuthenticationException subtypes — left unguarded, a malformed-JSON 200 response from
      // GitHub would propagate as a raw 500 instead of the same clean
      // SocialLoginAuthenticationFailureHandler redirect every other failure mode in this class
      // already gets via OAuth2AuthenticationException below. BR-DATA-01: never log the response
      // body itself (PII).
      throw new OAuth2AuthenticationException(
          new OAuth2Error(EMAILS_UNAVAILABLE_ERROR_CODE),
          "GitHub /user/emails response was not valid JSON",
          e);
    }
    return findPrimaryVerifiedEmail(emails);
  }

  // PMD.CyclomaticComplexity: the circuit breaker added one more genuinely distinct failure mode
  // (CallNotPermittedException) on top of the pre-existing IOException/InterruptedException split
  // — same shape ResendHttpClient#send's own identical suppression documents.
  // PMD.AvoidCatchingGenericException: the final catch (Exception e) is defensive-only, matching
  // Callable#call's own broad `throws Exception` signature executeCallable propagates.
  @SuppressWarnings({"PMD.CyclomaticComplexity", "PMD.AvoidCatchingGenericException"})
  private String sendEmailsRequest(final String accessToken) {
    final HttpRequest request =
        HttpRequest.newBuilder(emailsEndpoint)
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", "Bearer " + accessToken)
            .header("Accept", "application/vnd.github+json")
            .GET()
            .build();

    final HttpResponse<String> response;
    try {
      // SDE-III optimization pass, P2 point 4: same "fail fast on a dependency already known to
      // be down" reasoning this class's own Javadoc documents.
      response =
          circuitBreaker.executeCallable(
              () -> httpClient.send(request, HttpResponse.BodyHandlers.ofString()));
    } catch (final CallNotPermittedException e) {
      throw new OAuth2AuthenticationException(
          new OAuth2Error(EMAILS_UNAVAILABLE_ERROR_CODE),
          "GitHub /user/emails circuit breaker is open — GitHub appears to be down",
          e);
    } catch (final IOException e) {
      throw new OAuth2AuthenticationException(
          new OAuth2Error(EMAILS_UNAVAILABLE_ERROR_CODE), "GitHub /user/emails request failed", e);
    } catch (final InterruptedException e) {
      // Standard JDK pattern for a checked InterruptedException — same discipline as
      // ResendMailSender's own identical catch block.
      Thread.currentThread().interrupt();
      throw new OAuth2AuthenticationException(
          new OAuth2Error(EMAILS_UNAVAILABLE_ERROR_CODE),
          "GitHub /user/emails request interrupted",
          e);
    } catch (final Exception e) {
      // Unreachable in practice — see ResendHttpClient#send's own identical catch block.
      throw new OAuth2AuthenticationException(
          new OAuth2Error(EMAILS_UNAVAILABLE_ERROR_CODE),
          "GitHub /user/emails request failed unexpectedly",
          e);
    }

    if (response.statusCode() != SUCCESS_STATUS_CODE) {
      // BR-DATA-01: never log the response body — GitHub emails are PII. The status code alone is
      // enough to distinguish "GitHub is down/rate-limited" from a real result.
      throw new OAuth2AuthenticationException(
          new OAuth2Error(EMAILS_UNAVAILABLE_ERROR_CODE),
          "GitHub /user/emails responded with status " + response.statusCode());
    }
    return response.body();
  }

  private String findPrimaryVerifiedEmail(final JsonNode emails) {
    for (final JsonNode entry : emails) {
      if (entry.path("primary").asBoolean(false) && entry.path("verified").asBoolean(false)) {
        return entry.path("email").asString(null);
      }
    }
    return null;
  }
}
