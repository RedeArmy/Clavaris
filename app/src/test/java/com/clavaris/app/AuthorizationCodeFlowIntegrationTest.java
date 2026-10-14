package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.clavaris.app.infrastructure.adapter.out.security.TokenIssuanceEventLogger;
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
      "PLATFORM_BOOTSTRAP_CLIENT_SECRET=a-test-platform-secret",
      // Every test here asks the platform tier for its own access token, and the class now has more
      // tests than the default per-client token limit (20 a minute) allows.
      "clavaris.rate-limit.token.per-client-limit=500"
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

  // By class, not a hardcoded fully-qualified name — see PlatformTokenIssuanceIntegrationTest's
  // own identical fix (TD-ARCH-021) for why a string literal here silently broke once already.
  private static Logger tokenIssuanceLogger() {
    return (Logger) LoggerFactory.getLogger(TokenIssuanceEventLogger.class);
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
  // out (the new default) must actually show a consent screen, and approving it must still
  // complete a real, tokenizable authorization. Uses ["openid", "profile"] rather than ["openid"]
  // alone: ConsentController never renders a checkbox for "openid" itself (TD-SEC-011, matching
  // SAS's own former DefaultConsentPage — auto-granted once anything else is approved), so a
  // client requesting only "openid" would never present anything to actually consent to.
  //
  // TD-SEC-011 (2026-09-06): the consent screen is now this project's own ConsentController at
  // the flat /oauth2/consent path, reached via a real 302 from /o/{organizationId}/oauth2/authorize
  // — a deliberate protocol-shape change from the previous inline 200 render, not a regression.
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

    // Re-requesting the saved /authorize URL now 302s to this project's own consent page instead
    // of rendering inline or redirecting straight to a code — the exact behavior TD-SEC-026 found
    // missing, now reached through TD-SEC-011's own project-owned replacement.
    HttpResponse<Void> authorizeRedirect = getAbsoluteDiscardingBody(backToAuthorize);
    assertThat(authorizeRedirect.statusCode()).isEqualTo(302);
    String consentUrl = authorizeRedirect.headers().firstValue("Location").orElseThrow();
    assertThat(consentUrl).contains("/oauth2/consent");

    HttpResponse<String> consentPage = getAbsoluteWithBody(consentUrl);
    assertThat(consentPage.statusCode()).isEqualTo(200);
    assertThat(consentPage.body())
        .as("ConsentController's own identity/consent.html — see this class's own header comment")
        .contains("wants to access your account")
        .contains("name=\"scope\"")
        .contains("value=\"profile\"");
    // TD-SEC-011: the project-owned consent page needs no script/CDN at all — the plain strict
    // policy, not the weaker one SAS's own now-unreachable DefaultConsentPage used to need. Live
    // proof, not a read of ContentSecurityPolicyHeaderWriter.
    assertThat(consentPage.headers().firstValue("Content-Security-Policy"))
        .hasValueSatisfying(
            policy ->
                assertThat(policy)
                    .contains("frame-ancestors 'none'")
                    .doesNotContain("unsafe-inline"));

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
        // TD-PERF-024 (content-hash revision, 2026-09-14): the rendered src is now
        // /js/login-submit-guard-<hash>.js, not the bare path — see
        // StaticResourceVersioningIntegrationTest for the dedicated content-hashing proof; this
        // assertion only needs to confirm the right script is wired up, hash or no hash.
        .containsPattern("/js/login-submit-guard(-[0-9a-f]+)?\\.js");
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
  // must NOT result in a code. TD-SEC-011: identity/consent.html's own second, scope-free form
  // (see its own comment) produces this exact "no scope param at all" shape with no client-side
  // script needed, unlike SAS's former DefaultConsentPage Cancel button — this test submits that
  // same shape directly rather than driving the page's own form.
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
    HttpResponse<Void> authorizeRedirect = getAbsoluteDiscardingBody(backToAuthorize);
    String consentUrl = authorizeRedirect.headers().firstValue("Location").orElseThrow();
    HttpResponse<String> consentPage = getAbsoluteWithBody(consentUrl);
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

  // ---- OIDC Core §3.1.2.1 / §3.1.2.6: prompt and max_age ----

  @Test
  void promptNoneWithNoSignedInUserRedirectsLoginRequiredToTheRegisteredRedirectUri()
      throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID organizationId = createOrganization(platformToken, "Prompt None Co");
    ClientCredentials client = registerOAuthClient(platformToken, organizationId, false);

    HttpResponse<Void> response =
        authorize(organizationId, client.clientId(), REDIRECT_URI, "state-none", "&prompt=none");

    assertThat(response.statusCode()).isEqualTo(302);
    String location = response.headers().firstValue("Location").orElseThrow();
    assertThat(location).startsWith(REDIRECT_URI);
    assertThat(queryParam(location, "error")).isEqualTo("login_required");
    assertThat(queryParam(location, "state")).isEqualTo("state-none");
  }

  // An error must never be sent to a redirect_uri the client did not register (RFC 6749 §10.15).
  @Test
  void promptNoneNeverRedirectsToAnUnregisteredRedirectUri() throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID organizationId = createOrganization(platformToken, "Prompt None Evil Co");
    ClientCredentials client = registerOAuthClient(platformToken, organizationId, false);

    HttpResponse<Void> response =
        authorize(
            organizationId,
            client.clientId(),
            "https://evil.example/cb",
            "state-evil",
            "&prompt=none");

    assertThat(response.headers().firstValue("Location").orElse("")).doesNotContain("evil.example");
  }

  // ---- RFC 6749 §4.1.2.1 / OIDC Core §6: unsupported response_type, request, request_uri ----

  @Test
  void anUnsupportedResponseTypeRedirectsTheErrorToTheRegisteredRedirectUri() throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID organizationId = createOrganization(platformToken, "Response Type Co");
    ClientCredentials client = registerOAuthClient(platformToken, organizationId, false);

    HttpResponse<Void> response =
        getUnsupportedAuthorize(
            organizationId, client.clientId(), REDIRECT_URI, "response_type=token", "state-rt");

    assertThat(response.statusCode()).isEqualTo(302);
    String location = response.headers().firstValue("Location").orElseThrow();
    assertThat(location).startsWith(REDIRECT_URI);
    assertThat(queryParam(location, "error")).isEqualTo("unsupported_response_type");
    assertThat(queryParam(location, "state")).isEqualTo("state-rt");
  }

  @Test
  void anUnsupportedResponseTypeNeverRedirectsToAnUnregisteredRedirectUri() throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID organizationId = createOrganization(platformToken, "Response Type Evil Co");
    ClientCredentials client = registerOAuthClient(platformToken, organizationId, false);

    HttpResponse<Void> response =
        getUnsupportedAuthorize(
            organizationId,
            client.clientId(),
            "https://evil.example/cb",
            "response_type=id_token",
            "state-evil");

    assertThat(response.headers().firstValue("Location").orElse("")).doesNotContain("evil.example");
  }

  @Test
  void aRequestObjectAndARequestUriAreAnsweredWithTheirOwnErrorCodes() throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID organizationId = createOrganization(platformToken, "Request Object Co");
    ClientCredentials client = registerOAuthClient(platformToken, organizationId, false);

    HttpResponse<Void> byValue =
        authorize(organizationId, client.clientId(), REDIRECT_URI, "state-req", "&request=abc");
    HttpResponse<Void> byReference =
        authorize(
            organizationId,
            client.clientId(),
            REDIRECT_URI,
            "state-uri",
            "&request_uri=" + urlEncode("https://client.example/request.jwt"));

    assertThat(queryParam(byValue.headers().firstValue("Location").orElseThrow(), "error"))
        .isEqualTo("request_not_supported");
    assertThat(queryParam(byReference.headers().firstValue("Location").orElseThrow(), "error"))
        .isEqualTo("request_uri_not_supported");
  }

  @Test
  void discoveryDeclaresRequestObjectsUnsupported() throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID organizationId = createOrganization(platformToken, "Discovery Request Co");

    JsonNode discovery =
        objectMapper.readTree(
            getWithBody("/o/" + organizationId + "/.well-known/openid-configuration").body());

    assertThat(discovery.get("request_parameter_supported").asBoolean(true)).isFalse();
    assertThat(discovery.get("request_uri_parameter_supported").asBoolean(true)).isFalse();
  }

  // RFC 6749 §5.2: a failed Authorization-header client authentication is a 401 that challenges.
  @Test
  void aFailedBasicClientAuthenticationIsA401WithAWwwAuthenticateChallenge() throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID organizationId = createOrganization(platformToken, "Basic Challenge Co");
    ClientCredentials client = registerOAuthClient(platformToken, organizationId, false);
    String wrongBasic =
        Base64.getEncoder().encodeToString((client.clientId() + ":wrong-secret").getBytes());

    HttpResponse<String> response =
        httpClient.send(
            HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/oauth2/token"))
                .header("Authorization", "Basic " + wrongBasic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=authorization_code&code=x"))
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.headers().firstValue("WWW-Authenticate").orElse("")).startsWith("Basic");
    assertThat(objectMapper.readTree(response.body()).get("error").asString())
        .isEqualTo("invalid_client");
  }

  @Test
  void maxAgeZeroMakesASignedInUserAuthenticateAgainWithoutALoop() throws Exception {
    assertReauthenticates("&max_age=0", "max-age-zero@example.com");
  }

  @Test
  void promptLoginMakesASignedInUserAuthenticateAgainWithoutALoop() throws Exception {
    assertReauthenticates("&prompt=login", "prompt-login@example.com");
  }

  @Test
  void aMaxAgeLargerThanTheSessionAgeDoesNotAskForAnotherLogin() throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID organizationId = createOrganization(platformToken, "Max Age Fresh Co");
    ClientCredentials client = registerOAuthClient(platformToken, organizationId, false);
    signIn(organizationId, client, "max-age-fresh@example.com");

    HttpResponse<Void> response =
        authorize(organizationId, client.clientId(), REDIRECT_URI, "s", "&max_age=3600");

    assertThat(response.headers().firstValue("Location").orElseThrow())
        .startsWith(REDIRECT_URI)
        .contains("code=");
  }

  @Test
  void anInvalidMaxAgeIsRejectedAsInvalidRequestAtTheRegisteredRedirectUri() throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID organizationId = createOrganization(platformToken, "Max Age Invalid Co");
    ClientCredentials client = registerOAuthClient(platformToken, organizationId, false);
    signIn(organizationId, client, "max-age-invalid@example.com");

    HttpResponse<Void> response =
        authorize(organizationId, client.clientId(), REDIRECT_URI, "s", "&max_age=abc");

    String location = response.headers().firstValue("Location").orElseThrow();
    assertThat(location).startsWith(REDIRECT_URI);
    assertThat(queryParam(location, "error")).isEqualTo("invalid_request");
  }

  @Test
  void promptConsentShowsTheConsentScreenAgainEvenAfterTheScopesWereApproved() throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID organizationId = createOrganization(platformToken, "Prompt Consent Co");
    ClientCredentials client =
        registerOAuthClient(platformToken, organizationId, true, List.of("openid", "profile"));
    String email = "prompt-consent@example.com";
    registerAccount(organizationId, email, "a-correct-password");

    HttpResponse<Void> first =
        authorize(organizationId, client.clientId(), REDIRECT_URI, "s1", "", "openid profile");
    assertThat(first.statusCode()).isEqualTo(302);
    HttpResponse<Void> loginResponse =
        submitLogin(
            organizationId, fetchLoginCsrfToken(organizationId), email, "a-correct-password");
    HttpResponse<Void> toConsent =
        getAbsoluteDiscardingBody(loginResponse.headers().firstValue("Location").orElseThrow());
    String consentUrl = toConsent.headers().firstValue("Location").orElseThrow();
    assertThat(consentUrl).contains("/oauth2/consent");
    HttpResponse<String> consentPage = getAbsoluteWithBody(consentUrl);
    HttpResponse<Void> approved =
        submitConsent(
            organizationId, client.clientId(), extractState(consentPage.body()), "profile");
    assertThat(approved.headers().firstValue("Location").orElseThrow()).contains("code=");

    HttpResponse<Void> remembered =
        authorize(organizationId, client.clientId(), REDIRECT_URI, "s2", "", "openid profile");
    assertThat(remembered.headers().firstValue("Location").orElseThrow())
        .as("an approved consent is remembered, no screen without prompt=consent")
        .startsWith(REDIRECT_URI)
        .contains("code=");

    HttpResponse<Void> forced =
        authorize(
            organizationId,
            client.clientId(),
            REDIRECT_URI,
            "s3",
            "&prompt=consent",
            "openid profile");
    assertThat(forced.headers().firstValue("Location").orElseThrow()).contains("/oauth2/consent");
  }

  @Test
  void discoveryAdvertisesWhatClavarisActuallySupports() throws Exception {
    UUID organizationId = createOrganization(requestPlatformAccessToken(), "Discovery Co");

    HttpResponse<String> response =
        getWithBody("/o/" + organizationId + "/.well-known/openid-configuration");

    JsonNode metadata = objectMapper.readTree(response.body());
    assertThat(texts(metadata.get("scopes_supported")))
        .contains("openid", "profile", "email", "offline_access");
    assertThat(texts(metadata.get("claims_supported")))
        .contains("sub", "auth_time", "email", "email_verified", "name", "preferred_username");
    assertThat(texts(metadata.get("token_endpoint_auth_methods_supported")))
        .containsExactlyInAnyOrder("client_secret_basic", "client_secret_post");
    assertThat(metadata.has("tls_client_certificate_bound_access_tokens")).isFalse();
    assertThat(metadata.has("dpop_signing_alg_values_supported")).isFalse();
    assertThat(texts(metadata.get("code_challenge_methods_supported"))).containsExactly("S256");
  }

  private static List<String> texts(JsonNode array) {
    List<String> values = new java.util.ArrayList<>();
    array.forEach(node -> values.add(node.asString()));
    return values;
  }

  // Registers an Account and signs it in through the hosted login, leaving a live session behind.
  // OIDC Core §3.1.2.1: prompt=none with a signed-in End-User and no consent yet is
  // consent_required (never a consent page); the state comes back with it.
  @Test
  void promptNoneWithASessionButNoConsentIsConsentRequired() throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID organizationId = createOrganization(platformToken, "Prompt None No Consent Co");
    ClientCredentials client =
        registerOAuthClient(platformToken, organizationId, true, List.of("openid", "profile"));
    signIn(organizationId, client, "none-no-consent@example.com");

    HttpResponse<Void> response =
        authorize(
            organizationId,
            client.clientId(),
            REDIRECT_URI,
            "state-nc",
            "&prompt=none",
            "openid profile");

    String location = response.headers().firstValue("Location").orElseThrow();
    assertThat(location).startsWith(REDIRECT_URI);
    assertThat(queryParam(location, "error")).isEqualTo("consent_required");
    assertThat(queryParam(location, "state")).isEqualTo("state-nc");
  }

  // The same End-User once the consent exists: prompt=none succeeds silently with a code.
  @Test
  void promptNoneWithASessionAndAnExistingConsentIssuesACodeWithoutAnyScreen() throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID organizationId = createOrganization(platformToken, "Prompt None Consent Co");
    ClientCredentials client =
        registerOAuthClient(platformToken, organizationId, true, List.of("openid", "profile"));
    signInAndApproveConsent(organizationId, client, "none-consent@example.com");

    HttpResponse<Void> response =
        authorize(
            organizationId,
            client.clientId(),
            REDIRECT_URI,
            "state-c",
            "&prompt=none",
            "openid profile");

    String location = response.headers().firstValue("Location").orElseThrow();
    assertThat(location).startsWith(REDIRECT_URI);
    assertThat(queryParam(location, "code")).isNotBlank();
    assertThat(queryParam(location, "error")).isNull();
  }

  // prompt=consent: the screen is shown again, approving it issues a code, and the consent saved
  // by that approval is remembered afterwards (a plain authorize needs no screen again).
  @Test
  void promptConsentThenApproveIssuesACodeAndTheNewConsentIsRemembered() throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID organizationId = createOrganization(platformToken, "Prompt Consent Approve Co");
    ClientCredentials client =
        registerOAuthClient(platformToken, organizationId, true, List.of("openid", "profile"));
    signInAndApproveConsent(organizationId, client, "consent-approve@example.com");

    String forcedUrl =
        authorize(
                organizationId,
                client.clientId(),
                REDIRECT_URI,
                "s-forced",
                "&prompt=consent",
                "openid profile")
            .headers()
            .firstValue("Location")
            .orElseThrow();
    assertThat(forcedUrl).contains("/oauth2/consent");
    HttpResponse<Void> approved =
        submitConsent(
            organizationId,
            client.clientId(),
            extractState(getAbsoluteWithBody(forcedUrl).body()),
            "profile");
    assertThat(approved.headers().firstValue("Location").orElseThrow()).contains("code=");

    HttpResponse<Void> remembered =
        authorize(organizationId, client.clientId(), REDIRECT_URI, "s-after", "", "openid profile");
    assertThat(remembered.headers().firstValue("Location").orElseThrow())
        .startsWith(REDIRECT_URI)
        .contains("code=");
  }

  // prompt=consent removes the stored consent before the screen is shown, so an End-User who
  // leaves that screen without approving is asked again by the next plain authorize.
  @Test
  void abandoningAPromptConsentScreenLeavesTheNextAuthorizeAskingForConsentAgain()
      throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID organizationId = createOrganization(platformToken, "Prompt Consent Abandon Co");
    ClientCredentials client =
        registerOAuthClient(platformToken, organizationId, true, List.of("openid", "profile"));
    signInAndApproveConsent(organizationId, client, "consent-abandon@example.com");

    HttpResponse<Void> forced =
        authorize(
            organizationId,
            client.clientId(),
            REDIRECT_URI,
            "s-forced",
            "&prompt=consent",
            "openid profile");
    assertThat(forced.headers().firstValue("Location").orElseThrow()).contains("/oauth2/consent");

    HttpResponse<Void> next =
        authorize(organizationId, client.clientId(), REDIRECT_URI, "s-next", "", "openid profile");
    assertThat(next.headers().firstValue("Location").orElseThrow()).contains("/oauth2/consent");
  }

  // OIDC Core §2 / §12.2: auth_time is when the End-User authenticated. The code is exchanged
  // (and the Session opened) later, and a refresh later still; the ID token must report the same
  // auth_time throughout, not the Session's opening time or the refresh time.
  @Test
  void authTimeStaysTheLoginInstantAcrossTheCodeExchangeAndEveryRefresh() throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID organizationId = createOrganization(platformToken, "Auth Time Co");
    ClientCredentials client =
        registerOAuthClient(
            platformToken,
            organizationId,
            false,
            List.of("openid"),
            "\"authorization_code\",\"refresh_token\"");
    String email = "auth-time@example.com";
    signIn(organizationId, client, email);
    long loginAt = java.time.Instant.now().getEpochSecond();
    Thread.sleep(2200);

    String codeVerifier = generateCodeVerifier();
    HttpResponse<Void> authorized =
        authorize(
            organizationId,
            client.clientId(),
            REDIRECT_URI,
            "s1",
            "",
            "openid",
            deriveCodeChallenge(codeVerifier));
    String code = queryParam(authorized.headers().firstValue("Location").orElseThrow(), "code");
    JsonNode first =
        objectMapper.readTree(exchangeCode(organizationId, client, code, codeVerifier).body());
    long original = authTimeOf(first.get("id_token").asString());
    assertThat(original).isBetween(loginAt - 2, loginAt + 1);

    String refreshToken = first.get("refresh_token").asString();
    for (int i = 0; i < 2; i++) {
      Thread.sleep(1100);
      JsonNode refreshed =
          objectMapper.readTree(refresh(organizationId, client, refreshToken).body());
      assertThat(authTimeOf(refreshed.get("id_token").asString()))
          .as("refresh %s keeps the original auth_time", i + 1)
          .isEqualTo(original);
      refreshToken = refreshed.get("refresh_token").asString();
    }
  }

  @Test
  void anApprovedConsentIsStoredInPostgresAndForgottenByPromptConsent() throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID organizationId = createOrganization(platformToken, "Stored Consent Co");
    ClientCredentials client =
        registerOAuthClient(platformToken, organizationId, true, List.of("openid", "profile"));
    String email = "stored-consent@example.com";

    signInAndApproveConsent(organizationId, client, email);

    String sql =
        "select count(*) from oauth2_authorization_consent c"
            + " join accounts a on a.id::text = c.principal_name"
            + " where a.email = ? and a.organization_id = ?";
    assertThat(jdbcTemplate.queryForObject(sql, Integer.class, email, organizationId))
        .as("the approved consent is a row in Postgres, so it survives a restart")
        .isEqualTo(1);

    authorize(
        organizationId, client.clientId(), REDIRECT_URI, "s9", "&prompt=consent", "openid profile");

    assertThat(jdbcTemplate.queryForObject(sql, Integer.class, email, organizationId))
        .as("prompt=consent forgets the stored consent")
        .isZero();
  }

  private void signInAndApproveConsent(UUID organizationId, ClientCredentials client, String email)
      throws Exception {
    registerAccount(organizationId, email, "a-correct-password");
    authorize(organizationId, client.clientId(), REDIRECT_URI, "s1", "", "openid profile");
    HttpResponse<Void> loginResponse =
        submitLogin(
            organizationId, fetchLoginCsrfToken(organizationId), email, "a-correct-password");
    HttpResponse<Void> toConsent =
        getAbsoluteDiscardingBody(loginResponse.headers().firstValue("Location").orElseThrow());
    assertThat(toConsent.statusCode())
        .as("the saved authorize request resumes and redirects to the consent page")
        .isEqualTo(302);
    HttpResponse<String> consentPage =
        getAbsoluteWithBody(toConsent.headers().firstValue("Location").orElseThrow());
    HttpResponse<Void> approved =
        submitConsent(
            organizationId, client.clientId(), extractState(consentPage.body()), "profile");
    assertThat(approved.headers().firstValue("Location").orElseThrow()).contains("code=");
  }

  private void signIn(UUID organizationId, ClientCredentials client, String email)
      throws Exception {
    registerAccount(organizationId, email, "a-correct-password");
    getAuthorize(
        organizationId, client.clientId(), deriveCodeChallenge(generateCodeVerifier()), "init");
    HttpResponse<Void> login =
        submitLogin(
            organizationId, fetchLoginCsrfToken(organizationId), email, "a-correct-password");
    assertThat(login.statusCode()).isEqualTo(302);
  }

  // The contract for both prompt=login and max_age=0: the hosted login is shown again, and after
  // signing in the saved request resumes with a code (no redirect loop) whose ID token carries a
  // new auth_time.
  private void assertReauthenticates(String extraQuery, String email) throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID organizationId = createOrganization(platformToken, "Reauth Co " + extraQuery);
    ClientCredentials client = registerOAuthClient(platformToken, organizationId, false);
    signIn(organizationId, client, email);
    Thread.sleep(1100);
    long beforeReauthentication = java.time.Instant.now().getEpochSecond();
    Thread.sleep(1100);

    String codeVerifier = generateCodeVerifier();
    HttpResponse<Void> response =
        authorize(
            organizationId,
            client.clientId(),
            REDIRECT_URI,
            "reauth",
            extraQuery,
            "openid",
            deriveCodeChallenge(codeVerifier));
    assertThat(response.statusCode()).isEqualTo(302);
    assertThat(response.headers().firstValue("Location").orElseThrow())
        .as("the hosted login must be shown again, not a code")
        .contains("/login")
        .doesNotContain("code=");

    HttpResponse<Void> login =
        submitLogin(
            organizationId, fetchLoginCsrfToken(organizationId), email, "a-correct-password");
    HttpResponse<Void> resumed =
        getAbsoluteDiscardingBody(login.headers().firstValue("Location").orElseThrow());
    String location = resumed.headers().firstValue("Location").orElseThrow();
    assertThat(location).as("no loop: the resumed request is granted").startsWith(REDIRECT_URI);
    HttpResponse<String> token =
        exchangeCode(organizationId, client, queryParam(location, "code"), codeVerifier);
    assertThat(token.statusCode()).isEqualTo(200);
    String idToken = objectMapper.readTree(token.body()).get("id_token").asString();
    long authTime;
    try {
      authTime =
          parse(idToken).getJWTClaimsSet().getDateClaim("auth_time").toInstant().getEpochSecond();
    } catch (ParseException e) {
      throw new IllegalStateException(e);
    }
    assertThat(authTime)
        .as("auth_time reflects the new authentication, not the original login")
        .isGreaterThan(beforeReauthentication);
  }

  // OIDC Core §3.1.2.1: none must not be combined with another value, and a request that is not
  // allowed to show a page is answered with an error, never a login page.
  @Test
  void promptNoneCombinedWithAnotherValueIsInvalidRequestEvenWithASession() throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID organizationId = createOrganization(platformToken, "Prompt None Combined Co");
    ClientCredentials client = registerOAuthClient(platformToken, organizationId, false);
    signIn(organizationId, client, "none-combined@example.com");

    HttpResponse<Void> response =
        authorize(
            organizationId, client.clientId(), REDIRECT_URI, "state-nc2", "&prompt=none%20login");

    String location = response.headers().firstValue("Location").orElseThrow();
    assertThat(location).startsWith(REDIRECT_URI);
    assertThat(queryParam(location, "error")).isEqualTo("invalid_request");
    assertThat(queryParam(location, "state")).isEqualTo("state-nc2");
  }

  // §3.1.2.1 (max_age): an authentication older than max_age with prompt=none cannot be refreshed
  // without UI, so it is login_required rather than a silently granted code.
  @Test
  void promptNoneWithMaxAgeZeroAndASessionIsLoginRequired() throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID organizationId = createOrganization(platformToken, "Prompt None MaxAge Co");
    ClientCredentials client = registerOAuthClient(platformToken, organizationId, false);
    signIn(organizationId, client, "none-maxage@example.com");

    HttpResponse<Void> response =
        authorize(
            organizationId, client.clientId(), REDIRECT_URI, "state-ma", "&prompt=none&max_age=0");

    String location = response.headers().firstValue("Location").orElseThrow();
    assertThat(location).startsWith(REDIRECT_URI);
    assertThat(queryParam(location, "error")).isEqualTo("login_required");
    assertThat(queryParam(location, "code")).isNull();
  }

  // prompt=login with no session: the one login the End-User is shown must be enough; before, the
  // resumed request asked for a second one.
  @Test
  void promptLoginWithoutASessionNeedsOnlyOneLogin() throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID organizationId = createOrganization(platformToken, "Prompt Login One Co");
    ClientCredentials client = registerOAuthClient(platformToken, organizationId, false);
    String email = "login-once@example.com";
    signIn(organizationId, client, email);
    cookieManager.getCookieStore().removeAll();

    HttpResponse<Void> response =
        authorize(organizationId, client.clientId(), REDIRECT_URI, "state-once", "&prompt=login");
    assertThat(response.headers().firstValue("Location").orElseThrow()).contains("/login");
    HttpResponse<Void> login =
        submitLogin(
            organizationId, fetchLoginCsrfToken(organizationId), email, "a-correct-password");
    HttpResponse<Void> resumed =
        getAbsoluteDiscardingBody(login.headers().firstValue("Location").orElseThrow());

    String location = resumed.headers().firstValue("Location").orElseThrow();
    assertThat(location).as("granted after one login").startsWith(REDIRECT_URI);
    assertThat(queryParam(location, "code")).isNotBlank();
  }

  // A page on another site can make the browser send a request with the End-User's cookie (an
  // image tag is enough). That must never end their session or erase their consent.
  @Test
  void aCrossSiteSubresourceCannotEndTheSessionWithPromptLogin() throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID organizationId = createOrganization(platformToken, "Subresource Login Co");
    ClientCredentials client = registerOAuthClient(platformToken, organizationId, false);
    signIn(organizationId, client, "subresource-login@example.com");

    authorizeAs(organizationId, client.clientId(), "&prompt=login", "openid", "image");
    HttpResponse<Void> after =
        authorize(organizationId, client.clientId(), REDIRECT_URI, "state-after", "");

    assertThat(after.headers().firstValue("Location").orElseThrow())
        .as("the session must still be signed in")
        .startsWith(REDIRECT_URI)
        .contains("code=");
  }

  @Test
  void aCrossSiteSubresourceCannotForgetAStoredConsentWithPromptConsent() throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID organizationId = createOrganization(platformToken, "Subresource Consent Co");
    ClientCredentials client =
        registerOAuthClient(platformToken, organizationId, true, List.of("openid", "profile"));
    signInAndApproveConsent(organizationId, client, "subresource-consent@example.com");

    authorizeAs(organizationId, client.clientId(), "&prompt=consent", "openid profile", "image");
    HttpResponse<Void> after =
        authorize(
            organizationId, client.clientId(), REDIRECT_URI, "state-c2", "", "openid profile");

    assertThat(after.headers().firstValue("Location").orElseThrow())
        .as("the consent must still be remembered")
        .startsWith(REDIRECT_URI)
        .contains("code=");
  }

  // A navigation (what a browser does for the real flow) carries Sec-Fetch-Dest: document, or none
  // at all for a non-browser client; anything else is a subresource request.
  private HttpResponse<Void> authorizeAs(
      UUID organizationId, String clientId, String extraQuery, String scope, String fetchDest)
      throws IOException, InterruptedException {
    String query =
        "response_type=code&client_id="
            + clientId
            + "&redirect_uri="
            + urlEncode(REDIRECT_URI)
            + "&scope="
            + urlEncode(scope)
            + "&code_challenge="
            + deriveCodeChallenge(generateCodeVerifier())
            + "&code_challenge_method=S256&state=sub"
            + extraQuery;
    return httpClient.send(
        HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/oauth2/authorize?" + query))
            .header("Sec-Fetch-Dest", fetchDest)
            .header("Sec-Fetch-Site", "cross-site")
            .GET()
            .build(),
        HttpResponse.BodyHandlers.discarding());
  }

  private HttpResponse<Void> authorize(
      UUID organizationId, String clientId, String redirectUri, String state, String extraQuery)
      throws IOException, InterruptedException {
    return authorize(organizationId, clientId, redirectUri, state, extraQuery, "openid");
  }

  private HttpResponse<Void> authorize(
      UUID organizationId,
      String clientId,
      String redirectUri,
      String state,
      String extraQuery,
      String scope)
      throws IOException, InterruptedException {
    return authorize(
        organizationId,
        clientId,
        redirectUri,
        state,
        extraQuery,
        scope,
        deriveCodeChallenge(generateCodeVerifier()));
  }

  private HttpResponse<Void> authorize(
      UUID organizationId,
      String clientId,
      String redirectUri,
      String state,
      String extraQuery,
      String scope,
      String codeChallenge)
      throws IOException, InterruptedException {
    String query =
        "response_type=code&client_id="
            + clientId
            + "&redirect_uri="
            + urlEncode(redirectUri)
            + "&scope="
            + urlEncode(scope)
            + "&code_challenge="
            + codeChallenge
            + "&code_challenge_method=S256&state="
            + state
            + extraQuery;
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/oauth2/authorize?" + query))
            .GET()
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.discarding());
  }

  private HttpResponse<Void> getUnsupportedAuthorize(
      UUID organizationId, String clientId, String redirectUri, String responseType, String state)
      throws IOException, InterruptedException {
    String query =
        responseType
            + "&client_id="
            + clientId
            + "&redirect_uri="
            + urlEncode(redirectUri)
            + "&scope=openid&code_challenge="
            + deriveCodeChallenge(generateCodeVerifier())
            + "&code_challenge_method=S256&state="
            + state;
    return httpClient.send(
        HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/oauth2/authorize?" + query))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.discarding());
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
    return registerOAuthClient(
        platformToken, organizationId, requireConsent, allowedScopes, "\"authorization_code\"");
  }

  private ClientCredentials registerOAuthClient(
      String platformToken,
      UUID organizationId,
      boolean requireConsent,
      List<String> allowedScopes,
      String grantTypesJson)
      throws IOException, InterruptedException {
    String scopesJson =
        allowedScopes.stream().map(scope -> "\"" + scope + "\"").collect(Collectors.joining(","));
    String requestBody =
        """
        {
          "redirectUris": ["%s"],
          "allowedGrantTypes": [%s],
          "allowedScopes": [%s],
          "requireConsent": %s
        }
        """
            .formatted(REDIRECT_URI, grantTypesJson, scopesJson, requireConsent);
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

  private long authTimeOf(String idToken) {
    try {
      return parse(idToken)
          .getJWTClaimsSet()
          .getDateClaim("auth_time")
          .toInstant()
          .getEpochSecond();
    } catch (ParseException e) {
      throw new IllegalStateException(e);
    }
  }

  private HttpResponse<String> refresh(UUID organizationId, ClientCredentials client, String token)
      throws IOException, InterruptedException {
    String basicAuth =
        Base64.getEncoder()
            .encodeToString((client.clientId() + ":" + client.clientSecret()).getBytes());
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/oauth2/token"))
            .header("Authorization", "Basic " + basicAuth)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "grant_type=refresh_token&refresh_token=" + urlEncode(token)))
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
  // Session (DistributedSessionConfig), whose cookie is "SESSION_<organizationId>": one per tenant
  // since the tenants stopped sharing a session cookie, so this matches any of them (each test here
  // signs in to a single Organization).
  private String currentSessionId() {
    return cookieManager.getCookieStore().getCookies().stream()
        .filter(cookie -> cookie.getName().matches("(?i)SESSION(_[0-9a-f]{32})?"))
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
