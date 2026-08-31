package com.clavaris.app.infrastructure.config;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
import org.springframework.stereotype.Component;
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
 */
// PMD.LongVariable: every flagged name here (VERIFIED_EMAIL_ATTRIBUTE, GITHUB_REGISTRATION_ID,
// DEFAULT_GITHUB_EMAILS_ENDPOINT) names exactly what it is — see this class's own Javadoc.
// PMD.LawOfDemeter: userRequest.getClientRegistration()/getAccessToken() are the standard
// OAuth2UserRequest API shape — there is no other way to reach either, same rationale
// AntiAbuseRateLimitingFilter's own response.getWriter() suppression already documents.
// PMD.OnlyOneReturn: loadUser (delegate-vs-GitHub-specific) and findPrimaryVerifiedEmail
// (found-vs-none) each have two real, distinct outcomes — same "each outcome needs its own exit"
// rationale as SetRateLimitPolicyController's own identical suppression.
// @Component, not @Service, is intentional here — every infrastructure/config bean in this
// package (bridges, handlers, jobs, this one) uses @Component uniformly, regardless of how much
// logic it carries; this codebase reserves @Service-shaped semantics for actual domain/application
// use cases (application/usecase/**), which this OAuth2UserService adapter is not one of.
@SuppressWarnings({"PMD.LongVariable", "PMD.LawOfDemeter", "PMD.OnlyOneReturn"})
@Component
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
  // still makes the real call, same as before. Bounded via LinkedHashMap's own access-order LRU
  // eviction so this can never grow unbounded over a long-lived instance's uptime.
  private static final int MAX_CACHED_VERIFIED_EMAILS = 1000;
  private static final Duration VERIFIED_EMAIL_CACHE_TTL = Duration.ofMinutes(5);

  private final DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final URI emailsEndpoint;
  private final Map<String, CachedVerifiedEmail> verifiedEmailCache =
      Collections.synchronizedMap(
          new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(
                final Map.Entry<String, CachedVerifiedEmail> eldest) {
              return size() > MAX_CACHED_VERIFIED_EMAILS;
            }
          });

  private record CachedVerifiedEmail(String email, Instant expiresAt) {}

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
  @Autowired
  /* package */ GitHubVerifiedEmailUserService(
      final ObjectMapper objectMapper,
      @Value("${clavaris.oauth2.github.emails-uri:https://api.github.com/user/emails}")
          final String emailsUri) {
    this(HttpClient.newHttpClient(), objectMapper, URI.create(emailsUri));
  }

  // Test-only, same rationale as ResendMailSender's own identical second constructor — lets a test
  // inject a fully-controlled HttpClient/endpoint without a real network call.
  /* package */ GitHubVerifiedEmailUserService(
      final HttpClient httpClient, final ObjectMapper objectMapper, final URI emailsEndpoint) {
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;
    this.emailsEndpoint = emailsEndpoint;
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
    final Instant now = Instant.now();
    final CachedVerifiedEmail cached = verifiedEmailCache.get(githubUserId);
    if (cached != null && cached.expiresAt().isAfter(now)) {
      return cached.email();
    }
    final String verifiedEmail = fetchPrimaryVerifiedEmail(accessToken);
    verifiedEmailCache.put(
        githubUserId, new CachedVerifiedEmail(verifiedEmail, now.plus(VERIFIED_EMAIL_CACHE_TTL)));
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
      response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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
