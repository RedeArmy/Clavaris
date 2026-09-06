package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.clavaris.app.support.RedisBackedIntegrationTest;
import com.clavaris.app.support.TestMailSenderConfig;
import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.PlatformAccount;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.ParseException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The interactive Authorization Code + PKCE + real login flow, end to end, against real code — the
 * exact gap the original spike (0001) explicitly deferred ("Exercise the interactive Authorization
 * Code + PKCE + login flow end-to-end, not just client_credentials", its own §8 follow-up item #4)
 * and Task #19's own scope note deferred again ("the interactive Authorization Code + login flow is
 * not wired here yet").
 *
 * <p>Same "confirmed live, not assumed" bar as every other issuance test in this suite: a real
 * cryptographic signature check on the tokens that come out the other end, not just status codes —
 * plus every hop of the redirect chain a real browser would actually follow (unauthenticated {@code
 * /authorize} → redirect to the hosted login page → real credential check against a real registered
 * Account → redirect back to the original {@code /authorize} request, now authenticated →
 * authorization code → token exchange with the real PKCE verifier).
 *
 * <p>{@link TestMailSenderConfig}: the registration step below now really does trigger {@code
 * RequestEmailVerificationUseCase} (TD-SEC-004) — must never reach the real Resend API.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestMailSenderConfig.class)
@Testcontainers
@TestPropertySource(
    properties = {
      "PLATFORM_BOOTSTRAP_CLIENT_ID=test-platform-client",
      "PLATFORM_BOOTSTRAP_CLIENT_SECRET=a-test-platform-secret"
    })
class AuthorizationCodeFlowIntegrationTest extends RedisBackedIntegrationTest {

  private static final String REDIRECT_URI = "https://client.example.test/callback";
  private static final Pattern CSRF_TOKEN_PATTERN =
      Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Value("${local.server.port}")
  private int port;

  @Autowired private PlatformAccountRepository platformAccounts;
  @Autowired private JdbcTemplate jdbcTemplate;

  // TD-SEC-019: application.yml's own dev-only fallback for clavaris.oauth2.token-hash-secret —
  // not overridden by this class's @TestPropertySource, so this is the exact secret the running
  // app is actually keying HashedTokenOAuth2AuthorizationService with.
  private static final String TOKEN_HASH_SECRET =
      "dev-only-oauth2-token-hash-secret-change-in-real-deployments";

  // One CookieManager for the whole test — the session established while fetching the login
  // page's CSRF token, and the one Spring Security persists the authenticated SecurityContext
  // into after login, must be the SAME session throughout, exactly as a real browser would carry
  // it across every hop below. Kept as a field (not just handed to the builder) so the test can
  // also read the SESSION cookie's own value directly — see currentSessionId() below.
  private final CookieManager cookieManager = new CookieManager();
  private final HttpClient httpClient =
      HttpClient.newBuilder()
          .cookieHandler(cookieManager)
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final SecureRandom secureRandom = new SecureRandom();

  // TD-SEC-016: proves TokenIssuanceEventLogger fires wired into the real bean graph for the
  // organization tier specifically (OrganizationAuthorizationServerConfig's own
  // JwtGenerator.setJwtCustomizer(...) call) — the platform tier's wiring is proven separately by
  // PlatformTokenIssuanceIntegrationTest; the two tiers build their JwtGenerator independently, so
  // neither test's pass proves the other's wiring is correct.
  private final ListAppender<ILoggingEvent> tokenIssuanceLogAppender = new ListAppender<>();

  @BeforeEach
  void attachTokenIssuanceLogAppender() {
    tokenIssuanceLogAppender.start();
    tokenIssuanceLogger().addAppender(tokenIssuanceLogAppender);
  }

  @AfterEach
  void detachTokenIssuanceLogAppender() {
    tokenIssuanceLogger().detachAppender(tokenIssuanceLogAppender);
    tokenIssuanceLogAppender.stop();
    tokenIssuanceLogAppender.list.clear();
  }

  // By fully-qualified name, not TokenIssuanceEventLogger.class — that class is deliberately
  // package-private (same convention as every other class in app.infrastructure.config), and
  // Logback resolves loggers by name, so no import/visibility relaxation is needed to reach it.
  private static Logger tokenIssuanceLogger() {
    return (Logger)
        LoggerFactory.getLogger("com.clavaris.app.infrastructure.config.TokenIssuanceEventLogger");
  }

  @Test
  void completesARealPkceAuthorizationCodeFlowWithRealLoginEndToEnd() throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID organizationId = createOrganization(platformToken, "Auth Code Co");
    // TD-SEC-026/ADR-0017: requireConsent=false — a deliberately-trusted first-party client (the
    // real shape JobSeeker's own registration would take), so this flow proves the no-friction
    // path stays intact end to end. The consent-required path (the new default for any client
    // that doesn't opt out) gets its own dedicated tests below, not folded into this one.
    ClientCredentials client = registerOAuthClient(platformToken, organizationId, false);
    registerAccount(organizationId, "user@example.com", "a-correct-password");

    String codeVerifier = generateCodeVerifier();
    String codeChallenge = deriveCodeChallenge(codeVerifier);
    String state = "opaque-state-value";

    // 1. Unauthenticated /authorize -> the custom entry point redirects to the hosted login page,
    // scoped to this same Organization. ExceptionTranslationFilter has already saved this exact
    // request in the session's RequestCache by the time this redirect happens.
    HttpResponse<Void> authorizeAttempt =
        getAuthorize(organizationId, client.clientId(), codeChallenge, state);
    assertThat(authorizeAttempt.statusCode()).isEqualTo(302);
    String loginRedirect = authorizeAttempt.headers().firstValue("Location").orElseThrow();
    // ADR-0009 §1: the entry point now forwards client_id (translated onto the login page's own
    // clientId query-param name) so the login page can resolve embedding eligibility — this
    // assertion documents that forwarding, not just tolerates it.
    assertThat(loginRedirect)
        .endsWith("/o/" + organizationId + "/login?clientId=" + client.clientId());
    // TD-SEC-012 regression check: this is the pre-login session an attacker could have fixed
    // beforehand (RequestCache above just created it to remember this /authorize request).
    String sessionIdBeforeLogin = currentSessionId();
    assertThat(sessionIdBeforeLogin)
        .as("a session must exist before login for this to be a real regression check")
        .isNotBlank();

    // 2. GET the login form, extract its real CSRF token — /o/*/login is not one of SAS's own
    // endpoints, so it is NOT covered by OAuth2AuthorizationServerConfigurer's automatic CSRF
    // exemption; a real token is genuinely required here.
    String loginCsrfToken = fetchLoginCsrfToken(organizationId);

    // 3. Submit real credentials. On success, LoginController's AuthenticatedSessionEstablisher
    // persists the authenticated SecurityContext to the session and returns the RequestCache's
    // saved URL — the original /authorize request from step 1.
    HttpResponse<Void> loginResponse =
        submitLogin(organizationId, loginCsrfToken, "user@example.com", "a-correct-password");
    assertThat(loginResponse.statusCode()).isEqualTo(302);
    String backToAuthorize = loginResponse.headers().firstValue("Location").orElseThrow();
    assertThat(backToAuthorize).contains("/oauth2/authorize");
    // TD-SEC-012: a real session-fixation regression check, not just a code-reading claim — the
    // session ID must differ from the pre-login one captured above. This only passes because
    // SpringSecurityAuthenticatedSessionEstablisher.establish() calls request.changeSessionId()
    // before attaching the authenticated SecurityContext; it also only passes at all because
    // changeSessionId() preserves session attributes — if it didn't, the RequestCache's saved
    // /oauth2/authorize request (captured before login) would be lost and step 4 below would fail.
    assertThat(currentSessionId())
        .as("session ID must rotate on successful login (CWE-384)")
        .isNotBlank()
        .isNotEqualTo(sessionIdBeforeLogin);

    // 4. Re-request the saved /authorize URL — now authenticated (same session cookie) — SAS
    // issues a real authorization code and redirects to the client's own redirect_uri.
    HttpResponse<Void> authorizedResponse = getAbsoluteDiscardingBody(backToAuthorize);
    assertThat(authorizedResponse.statusCode()).isEqualTo(302);
    String redirectWithCode = authorizedResponse.headers().firstValue("Location").orElseThrow();
    assertThat(redirectWithCode).startsWith(REDIRECT_URI);
    String code = queryParam(redirectWithCode, "code");
    assertThat(code).isNotBlank();
    assertThat(queryParam(redirectWithCode, "state")).isEqualTo(state);
    // TD-SEC-019: the code SAS just handed the client back must already be hash-stored, not
    // written in plaintext then hashed only later at exchange time.
    assertOnlyTheHashedValueIsStored(Column.AUTHORIZATION_CODE_VALUE, code);

    // Isolate what's checked below to this exchange specifically — requestPlatformAccessToken()
    // above (bootstrapping the organization/client via the management API) already logged its own
    // event=token_issued line for that unrelated client_credentials token.
    tokenIssuanceLogAppender.list.clear();

    // 5. Exchange the code for tokens, presenting the real PKCE verifier.
    HttpResponse<String> tokenResponse = exchangeCode(organizationId, client, code, codeVerifier);
    assertThat(tokenResponse.statusCode()).isEqualTo(200);
    JsonNode tokenBody = objectMapper.readTree(tokenResponse.body());
    String accessToken = tokenBody.get("access_token").asString();
    String idToken = tokenBody.get("id_token").asString();
    assertThat(accessToken).isNotBlank();
    assertThat(idToken)
        .as("scope=openid must yield a real ID token, not just an access token")
        .isNotBlank();
    // TD-SEC-019: the token exchange's own two token types must equally never land in
    // oauth2_authorization in plaintext — the platform tier's own access-token case is covered
    // separately (PlatformTokenIssuanceIntegrationTest); this is the ID token's only coverage.
    assertOnlyTheHashedValueIsStored(Column.ACCESS_TOKEN_VALUE, accessToken);
    assertOnlyTheHashedValueIsStored(Column.OIDC_ID_TOKEN_VALUE, idToken);
    // TD-SEC-016: both the access token and the ID token this exchange just issued must have
    // logged their own event=token_issued line — proves the wiring, not just the customizer's own
    // isolated behaviour (TokenIssuanceEventLoggerTest covers that).
    List<String> tokenIssuedMessages =
        tokenIssuanceLogAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    assertThat(tokenIssuedMessages)
        .as("one event=token_issued line per token type this exchange issued")
        .hasSize(2)
        .allSatisfy(
            message ->
                assertThat(message)
                    .contains("event=token_issued")
                    .contains("grantType=authorization_code")
                    .contains("clientId=" + client.clientId()))
        .anyMatch(message -> message.contains("tokenType=access_token"))
        .anyMatch(message -> message.contains("tokenType=id_token"));

    // 6. Cryptographic proof, not just a 200 — same bar as every other issuance test in this
    // suite: both tokens must actually verify against this Organization's own published JWKS.
    JWKSet jwks = parseJwkSet(getWithBody("/o/" + organizationId + "/oauth2/jwks").body());
    assertThat(verify(parse(accessToken), jwks)).isTrue();
    assertThat(verify(parse(idToken), jwks)).isTrue();

    // ADR-0016 / ISO-IEC-29115: the real, signed ID token this exchange issued must carry acr/amr —
    // extracted to its own helper (same pattern as assertOnlyTheHashedValueIsStored below), partly
    // for readability and partly to keep this method's own direct assertion count under
    // SonarCloud's S5961 threshold now that this flow has this many distinct properties to verify.
    assertIdTokenCarriesAuthenticationContextClaims(idToken);
  }

  // AuthenticationContextClaimsCustomizerTest already proves the customizer's own isolated
  // behaviour; this is the wiring proof, the same "prove it end to end, not just the unit" bar
  // TD-SEC-016's own token_issued log assertion in the caller already applies.
  private static void assertIdTokenCarriesAuthenticationContextClaims(final String idToken)
      throws ParseException {
    JWTClaimsSet idTokenClaims = parse(idToken).getJWTClaimsSet();
    assertThat(idTokenClaims.getStringClaim("acr")).isEqualTo("urn:clavaris:loa:2");
    assertThat(idTokenClaims.getStringListClaim("amr")).containsExactly("pwd");
  }

  @Test
  void rejectsATokenExchangeWithTheWrongPkceVerifier() throws Exception {
    // BR-CLIENT-03: PKCE is mandatory, and the verifier presented at exchange time must actually
    // match the challenge presented at the start — a stolen authorization code alone must not be
    // enough to redeem a token.
    String platformToken = requestPlatformAccessToken();
    UUID organizationId = createOrganization(platformToken, "PKCE Co");
    // requireConsent=false — this test is about PKCE verifier mismatch, not consent; skipping
    // consent keeps it focused on the one thing it actually exercises.
    ClientCredentials client = registerOAuthClient(platformToken, organizationId, false);
    registerAccount(organizationId, "pkce-user@example.com", "a-correct-password");

    String realCodeVerifier = generateCodeVerifier();
    String codeChallenge = deriveCodeChallenge(realCodeVerifier);

    HttpResponse<Void> authorizeAttempt =
        getAuthorize(organizationId, client.clientId(), codeChallenge, "state");
    String loginRedirect = authorizeAttempt.headers().firstValue("Location").orElseThrow();
    // ADR-0009 §1: the entry point now forwards client_id (translated onto the login page's own
    // clientId query-param name) so the login page can resolve embedding eligibility — this
    // assertion documents that forwarding, not just tolerates it.
    assertThat(loginRedirect)
        .endsWith("/o/" + organizationId + "/login?clientId=" + client.clientId());
    String loginCsrfToken = fetchLoginCsrfToken(organizationId);
    HttpResponse<Void> loginResponse =
        submitLogin(organizationId, loginCsrfToken, "pkce-user@example.com", "a-correct-password");
    String backToAuthorize = loginResponse.headers().firstValue("Location").orElseThrow();
    HttpResponse<Void> authorizedResponse = getAbsoluteDiscardingBody(backToAuthorize);
    String redirectWithCode = authorizedResponse.headers().firstValue("Location").orElseThrow();
    String code = queryParam(redirectWithCode, "code");

    HttpResponse<String> tokenResponse =
        exchangeCode(organizationId, client, code, generateCodeVerifier());

    assertThat(tokenResponse.statusCode()).isEqualTo(400);
  }

  // TD-SEC-026/ADR-0017: the real, previously-dormant gap this closed — a client that doesn't opt
  // out (the new default) must actually show SAS's own consent screen, and approving it must
  // still complete a real, tokenizable authorization. Uses ["openid", "profile"] rather than
  // ["openid"] alone: SAS's own DefaultConsentPage never renders a checkbox for "openid" itself
  // (decompiled confirmation, DefaultConsentPage.generateConsentPage — treated as always-granted
  // once anything else is approved), so a client requesting only "openid" would never present
  // anything to actually consent to.
  @Test
  void completesARealPkceAuthorizationCodeFlowThroughARealConsentScreen() throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID organizationId = createOrganization(platformToken, "Consent Co");
    ClientCredentials client =
        registerOAuthClient(platformToken, organizationId, true, List.of("openid", "profile"));
    registerAccount(organizationId, "consent-user@example.com", "a-correct-password");

    String codeVerifier = generateCodeVerifier();
    String codeChallenge = deriveCodeChallenge(codeVerifier);

    getAuthorize(organizationId, client.clientId(), codeChallenge, "state", "openid profile");
    String loginCsrfToken = fetchLoginCsrfToken(organizationId);
    HttpResponse<Void> loginResponse =
        submitLogin(
            organizationId, loginCsrfToken, "consent-user@example.com", "a-correct-password");
    String backToAuthorize = loginResponse.headers().firstValue("Location").orElseThrow();

    // Re-requesting the saved /authorize URL now renders SAS's own consent page instead of
    // redirecting straight to a code — the exact behavior TD-SEC-026 found missing.
    HttpResponse<String> consentPage = getAbsoluteWithBody(backToAuthorize);
    assertThat(consentPage.statusCode()).isEqualTo(200);
    assertThat(consentPage.body())
        .as("SAS's own DefaultConsentPage — see this class's own header comment")
        .contains("Consent required")
        .contains("name=\"scope\" value=\"profile\"");
    // TD-SEC-009: the consent page's own relaxed CSP must actually be present on this exact
    // response, not just configured — live proof, not a read of ContentSecurityPolicyHeaderWriter.
    assertThat(consentPage.headers().firstValue("Content-Security-Policy"))
        .hasValueSatisfying(policy -> assertThat(policy).contains("stackpath.bootstrapcdn.com"));

    HttpResponse<Void> approvedResponse =
        submitConsent(
            organizationId, client.clientId(), extractState(consentPage.body()), "profile");
    assertThat(approvedResponse.statusCode()).isEqualTo(302);
    String redirectWithCode = approvedResponse.headers().firstValue("Location").orElseThrow();
    assertThat(redirectWithCode).startsWith(REDIRECT_URI);
    String code = queryParam(redirectWithCode, "code");
    assertThat(code).isNotBlank();

    // The code approving consent just issued must exchange for real, cryptographically verifiable
    // tokens — same bar as the no-consent flagship test, proving consent isn't a dead end that
    // happens to redirect but never actually completes the grant.
    HttpResponse<String> tokenResponse = exchangeCode(organizationId, client, code, codeVerifier);
    assertThat(tokenResponse.statusCode()).isEqualTo(200);
    JsonNode tokenBody = objectMapper.readTree(tokenResponse.body());
    String accessToken = tokenBody.get("access_token").asString();
    assertThat(accessToken).isNotBlank();
    JWKSet jwks = parseJwkSet(getWithBody("/o/" + organizationId + "/oauth2/jwks").body());
    assertThat(verify(parse(accessToken), jwks)).isTrue();
  }

  // Code review finding (2026-09-01): the login page's own CSP carve-out and its real static
  // script must both actually be live on a real running instance, not just configured — same
  // "live proof, not a read of ContentSecurityPolicyHeaderWriter" bar as the consent-page CSP
  // assertion above (TD-SEC-009).
  @Test
  void theLoginPageLoadsItsOwnSubmitGuardScriptUnderItsOwnRelaxedCsp() throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID organizationId = createOrganization(platformToken, "Submit Guard Co");

    HttpResponse<String> loginPage = getWithBody("/o/" + organizationId + "/login");
    assertThat(loginPage.statusCode()).isEqualTo(200);
    assertThat(loginPage.body())
        .as("login.html must wire up the mutex on its own form and load the script")
        .contains("data-login-form")
        .contains("/js/login-submit-guard.js");
    // The login page's own policy: real script-src, same-origin only — neither the strict
    // default (script-src 'none') nor the consent page's ('unsafe-inline').
    assertThat(loginPage.headers().firstValue("Content-Security-Policy"))
        .hasValueSatisfying(
            policy ->
                assertThat(policy).contains("script-src 'self'").doesNotContain("unsafe-inline"));

    // The script itself must actually be reachable — DefaultSecurityConfig's catch-all chain is
    // what's expected to serve it (see ContentSecurityPolicyHeaderWriter's own Javadoc), not
    // assumed from Spring Boot's default static-resource wiring alone.
    HttpResponse<String> script = getWithBody("/js/login-submit-guard.js");
    assertThat(script.statusCode()).isEqualTo(200);
    assertThat(script.body())
        .as("must be this project's own guard script, not an empty/placeholder 404 page")
        .contains("clavaris_login_submit_lock");
  }

  // TD-SEC-026/ADR-0017: the other real half of the invariant — a real user genuinely declining
  // must NOT result in a code, matching DefaultConsentPage's own Cancel button (which resets the
  // form, unchecking every scope, then submits — the same "no scope param at all" shape this test
  // submits directly rather than driving the page's own JS).
  @Test
  void decliningConsentRedirectsWithAccessDeniedAndIssuesNoCode() throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID organizationId = createOrganization(platformToken, "Consent Decline Co");
    ClientCredentials client =
        registerOAuthClient(platformToken, organizationId, true, List.of("openid", "profile"));
    registerAccount(organizationId, "decline-user@example.com", "a-correct-password");

    String codeChallenge = deriveCodeChallenge(generateCodeVerifier());
    getAuthorize(organizationId, client.clientId(), codeChallenge, "state", "openid profile");
    String loginCsrfToken = fetchLoginCsrfToken(organizationId);
    HttpResponse<Void> loginResponse =
        submitLogin(
            organizationId, loginCsrfToken, "decline-user@example.com", "a-correct-password");
    String backToAuthorize = loginResponse.headers().firstValue("Location").orElseThrow();
    HttpResponse<String> consentPage = getAbsoluteWithBody(backToAuthorize);
    String state = extractState(consentPage.body());

    // No "scope" parameter at all — an empty authorizedScopes set is exactly what SAS's own
    // OAuth2AuthorizationConsentAuthenticationProvider treats as a declined consent (decompiled
    // confirmation: an empty resulting authorities set removes the pending authorization and
    // throws access_denied), the same effect the page's own Cancel button produces via JS.
    HttpResponse<Void> declinedResponse =
        submitConsent(organizationId, client.clientId(), state, null);

    assertThat(declinedResponse.statusCode()).isEqualTo(302);
    String redirect = declinedResponse.headers().firstValue("Location").orElseThrow();
    assertThat(redirect).startsWith(REDIRECT_URI);
    assertThat(queryParam(redirect, "error")).isEqualTo("access_denied");
    assertThat(queryParam(redirect, "code"))
        .as("a declined consent must never yield a code")
        .isNull();
  }

  private String requestPlatformAccessToken() throws IOException, InterruptedException {
    String basicAuth =
        Base64.getEncoder()
            .encodeToString("test-platform-client:a-test-platform-secret".getBytes());
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/oauth2/token"))
            .header("Authorization", "Basic " + basicAuth)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "grant_type=client_credentials&scope=platform:organizations:write"))
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    return objectMapper.readTree(response.body()).get("access_token").asString();
  }

  private UUID createOrganization(String platformToken, String name)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/api/v1/admin/organizations"))
            .header("Authorization", "Bearer " + platformToken)
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"name\":\""
                        + name
                        + "\",\"ownerPlatformAccountId\":\""
                        + registerAPlatformAccount()
                        + "\"}"))
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    return UUID.fromString(objectMapper.readTree(response.body()).get("id").asString());
  }

  // A real PlatformAccount row, written directly through the repository rather than the full
  // /platform/register + /platform/verify-email HTTP flow — CreateOrganizationService (security
  // finding, SDE-III review, 2026-08-22) now validates ownerPlatformAccountId against a real row.
  private UUID registerAPlatformAccount() {
    PlatformAccount account =
        PlatformAccount.register(new Email("owner-" + UUID.randomUUID() + "@example.test"));
    // JpaPlatformAccountRepository.save() rejects the credential-less intermediate state
    // PlatformAccount.register() itself allows — a real hash is irrelevant here, this suite
    // never logs in as this account, only needs its row to exist for the owner check.
    account.attachPasswordCredential("not-a-real-hash-this-test-never-logs-in");
    platformAccounts.save(account);
    return account.id().value();
  }

  private ClientCredentials registerOAuthClient(
      String platformToken, UUID organizationId, boolean requireConsent)
      throws IOException, InterruptedException {
    return registerOAuthClient(platformToken, organizationId, requireConsent, List.of("openid"));
  }

  private ClientCredentials registerOAuthClient(
      String platformToken, UUID organizationId, boolean requireConsent, List<String> allowedScopes)
      throws IOException, InterruptedException {
    String scopesJson =
        allowedScopes.stream().map(scope -> "\"" + scope + "\"").collect(Collectors.joining(","));
    String requestBody =
        """
        {
          "redirectUris": ["%s"],
          "allowedGrantTypes": ["authorization_code"],
          "allowedScopes": [%s],
          "requireConsent": %s
        }
        """
            .formatted(REDIRECT_URI, scopesJson, requireConsent);
    HttpRequest request =
        HttpRequest.newBuilder(
                baseUri("/api/v1/admin/organizations/" + organizationId + "/clients"))
            .header("Authorization", "Bearer " + platformToken)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    JsonNode body = objectMapper.readTree(response.body());
    return new ClientCredentials(
        body.get("clientId").asString(), body.get("clientSecret").asString());
  }

  private void registerAccount(UUID organizationId, String email, String password)
      throws IOException, InterruptedException {
    HttpRequest getForm =
        HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/register")).GET().build();
    HttpResponse<String> formResponse =
        httpClient.send(getForm, HttpResponse.BodyHandlers.ofString());
    Matcher matcher = CSRF_TOKEN_PATTERN.matcher(formResponse.body());
    assertThat(matcher.find()).isTrue();
    String csrfToken = matcher.group(1);

    String body =
        "_csrf="
            + csrfToken
            + "&email="
            + email
            + "&password="
            + password
            + "&confirmPassword="
            + password;
    HttpRequest register =
        HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/register"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    HttpResponse<Void> response = httpClient.send(register, HttpResponse.BodyHandlers.discarding());
    assertThat(response.statusCode()).isEqualTo(302);
  }

  private HttpResponse<Void> getAuthorize(
      UUID organizationId, String clientId, String codeChallenge, String state)
      throws IOException, InterruptedException {
    return getAuthorize(organizationId, clientId, codeChallenge, state, "openid");
  }

  private HttpResponse<Void> getAuthorize(
      UUID organizationId, String clientId, String codeChallenge, String state, String scope)
      throws IOException, InterruptedException {
    String query =
        "response_type=code"
            + "&client_id="
            + clientId
            + "&redirect_uri="
            + urlEncode(REDIRECT_URI)
            + "&scope="
            + urlEncode(scope)
            + "&code_challenge="
            + codeChallenge
            + "&code_challenge_method=S256"
            + "&state="
            + state;
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/oauth2/authorize?" + query))
            .GET()
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.discarding());
  }

  private String fetchLoginCsrfToken(UUID organizationId) throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/login")).GET().build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    Matcher matcher = CSRF_TOKEN_PATTERN.matcher(response.body());
    assertThat(matcher.find()).as("login.html must render a _csrf hidden input").isTrue();
    return matcher.group(1);
  }

  private HttpResponse<Void> submitLogin(
      UUID organizationId, String csrfToken, String email, String password)
      throws IOException, InterruptedException {
    String body = "_csrf=" + csrfToken + "&email=" + email + "&password=" + password;
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/login"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.discarding());
  }

  private HttpResponse<String> exchangeCode(
      UUID organizationId, ClientCredentials client, String code, String codeVerifier)
      throws IOException, InterruptedException {
    String basicAuth =
        Base64.getEncoder()
            .encodeToString((client.clientId() + ":" + client.clientSecret()).getBytes());
    String body =
        "grant_type=authorization_code"
            + "&code="
            + code
            + "&redirect_uri="
            + urlEncode(REDIRECT_URI)
            + "&code_verifier="
            + codeVerifier;
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/oauth2/token"))
            .header("Authorization", "Basic " + basicAuth)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<Void> getAbsoluteDiscardingBody(String absoluteUrl)
      throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder(URI.create(absoluteUrl)).GET().build();
    return httpClient.send(request, HttpResponse.BodyHandlers.discarding());
  }

  // Same as getAbsoluteDiscardingBody, but keeps the body — needed once a real consent screen can
  // render here instead of always redirecting straight through (TD-SEC-026/ADR-0017).
  private HttpResponse<String> getAbsoluteWithBody(String absoluteUrl)
      throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder(URI.create(absoluteUrl)).GET().build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> getWithBody(String path) throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder(baseUri(path)).GET().build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  // DefaultConsentPage's own hidden <input name="state" value="..."> — decompiled confirmation,
  // this class's own header comment. The state value is an opaque token SAS itself minted (not
  // the caller-supplied "state"/"opaque-state-value" query param from the original /authorize
  // request — those are two different things SAS happens to both call "state"), so it must be
  // read back from the page, never reconstructed by the test.
  private static final Pattern CONSENT_STATE_PATTERN =
      Pattern.compile("name=\"state\" value=\"([^\"]+)\"");

  private static String extractState(String consentPageHtml) {
    Matcher matcher = CONSENT_STATE_PATTERN.matcher(consentPageHtml);
    assertThat(matcher.find()).as("consent page must render a hidden state input").isTrue();
    return matcher.group(1);
  }

  // Posts back to SAS's own consent-handling endpoint — same URI the GET above was served from,
  // per DefaultConsentPage's own <form action="{request.getRequestURI()}">. A null scope submits
  // no "scope" parameter at all (the real shape of a declined consent, see
  // decliningConsentRedirectsWithAccessDeniedAndIssuesNoCode's own comment) rather than an empty
  // string, which is a different, not-equivalent form encoding.
  private HttpResponse<Void> submitConsent(
      UUID organizationId, String clientId, String state, String approvedScope)
      throws IOException, InterruptedException {
    String body =
        "client_id="
            + urlEncode(clientId)
            + "&state="
            + urlEncode(state)
            + (approvedScope != null ? "&scope=" + urlEncode(approvedScope) : "");
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/oauth2/authorize"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.discarding());
  }

  private URI baseUri(String path) {
    return URI.create("http://localhost:" + port + path);
  }

  // Reads the real cookie the container sent, not an assumption about internal session-tracking
  // state — the only way to actually observe from outside the process whether the session ID
  // changed, which is the entire point of the TD-SEC-012 regression checks above. "SESSION", not
  // "JSESSIONID" — TD-ARCH-002 replaced the servlet container's own session tracking with Spring
  // Session (DistributedSessionConfig), whose DefaultCookieSerializer names its cookie "SESSION".
  private String currentSessionId() {
    return cookieManager.getCookieStore().getCookies().stream()
        .filter(cookie -> "SESSION".equalsIgnoreCase(cookie.getName()))
        .map(HttpCookie::getValue)
        .findFirst()
        .orElse(null);
  }

  private String generateCodeVerifier() {
    byte[] bytes = new byte[32];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private String deriveCodeChallenge(String codeVerifier) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available on this JVM", e);
    }
  }

  private static String urlEncode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String queryParam(String url, String name) {
    String query = URI.create(url).getRawQuery();
    for (String pair : query.split("&")) {
      int equalsIndex = pair.indexOf('=');
      if (equalsIndex >= 0 && pair.substring(0, equalsIndex).equals(name)) {
        return URLDecoder.decode(pair.substring(equalsIndex + 1), StandardCharsets.UTF_8);
      }
    }
    return null;
  }

  // TD-SEC-019: computes the expected HMAC independently (javax.crypto.Mac, not
  // BearerTokenHasher itself — that class is package-private to infrastructure.config and
  // unreachable from this package, and duplicating the computation here also avoids a
  // tautological test that would pass even if BearerTokenHasher itself were broken).
  private void assertOnlyTheHashedValueIsStored(Column column, String rawValue) throws Exception {
    String expectedHashedValue = hmacSha256Hex(TOKEN_HASH_SECRET, rawValue);

    List<String> rowsMatchingTheRawValue =
        jdbcTemplate.queryForList(column.selectByValueSql(), String.class, rawValue);
    List<String> rowsMatchingTheExpectedHash =
        jdbcTemplate.queryForList(column.selectByValueSql(), String.class, expectedHashedValue);

    assertThat(rowsMatchingTheRawValue)
        .as("the raw, directly-usable value must never appear in oauth2_authorization." + column)
        .isEmpty();
    assertThat(rowsMatchingTheExpectedHash)
        .as("exactly the HMAC-SHA256 digest of the real value must be what was actually stored")
        .hasSize(1);
  }

  // A fixed enum, not a raw column-name string, so the SQL built in selectByValueSql() is never
  // built from arbitrary caller-supplied text — the three real oauth2_authorization columns
  // HashedTokenOAuth2AuthorizationService actually hashes (its own Javadoc).
  private enum Column {
    AUTHORIZATION_CODE_VALUE("authorization_code_value"),
    ACCESS_TOKEN_VALUE("access_token_value"),
    OIDC_ID_TOKEN_VALUE("oidc_id_token_value");

    private final String columnName;

    Column(String columnName) {
      this.columnName = columnName;
    }

    String selectByValueSql() {
      return "SELECT id FROM oauth2_authorization WHERE " + columnName + " = ?";
    }
  }

  private static String hmacSha256Hex(String secret, String value) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
  }

  private static SignedJWT parse(String token) {
    try {
      return SignedJWT.parse(token);
    } catch (ParseException e) {
      throw new IllegalStateException("Issued token is not a parseable JWT", e);
    }
  }

  private static JWKSet parseJwkSet(String json) {
    try {
      return JWKSet.parse(json);
    } catch (ParseException e) {
      throw new IllegalStateException("Published JWKS document is not parseable", e);
    }
  }

  private static boolean verify(SignedJWT jwt, JWKSet jwks) {
    JWK key = jwks.getKeyByKeyId(jwt.getHeader().getKeyID());
    assertThat(key).as("the token's own kid must be present in the published JWKS").isNotNull();
    try {
      JWSVerifier verifier = new RSASSAVerifier(((RSAKey) key).toRSAPublicKey());
      return jwt.verify(verifier);
    } catch (JOSEException e) {
      throw new IllegalStateException("Signature verification itself failed to run", e);
    }
  }

  private record ClientCredentials(String clientId, String clientSecret) {}
}
