package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.clavaris.app.support.RedisBackedIntegrationTest;
import com.clavaris.app.support.TestMailSenderConfig;
import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.application.usecase.requestemailverification.MailSender;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.PlatformAccount;
import com.clavaris.identity.domain.model.SocialProvider;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/**
 * ADR-0020: the real GitHub OAuth2 client dance, end to end, against real code — every hop a real
 * browser would follow (the "Sign in with GitHub" link's own redirect → Spring Security's own
 * {@code OAuth2AuthorizationRequestRedirectFilter} building the real authorization URL → the
 * provider's own redirect back → {@code OAuth2LoginAuthenticationFilter} exchanging the code and
 * fetching the profile → {@link
 * com.clavaris.app.infrastructure.adapter.out.security.GitHubVerifiedEmailUserService}'s own second
 * {@code GET /user/emails} call → {@code SocialLoginAuthenticationSuccessHandler} routing into the
 * real use case and establishing a real session) — same "confirmed live, not assumed" bar {@link
 * AuthorizationCodeFlowIntegrationTest} already set for the password-based flow.
 *
 * <p>GitHub, not Google: no OIDC ID-token/JWKS machinery to simulate, so the provider side of this
 * test is three plain JSON stub endpoints ({@code token}/{@code user}/{@code user/emails}) rather
 * than a full mock OpenID Provider — proportionate to what this test needs to prove, and it
 * directly exercises the one piece of ADR-0020 that's genuinely novel (the extra verified-email
 * lookup). {@code spring.security.oauth2.client.provider.github.*} is overridden via {@link
 * DynamicPropertySource} to point at that local stub instead of the real api.github.com — the stub
 * server itself must already be listening before Spring's context boots, since these are static
 * properties resolved once at context-creation time.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestMailSenderConfig.class)
@Testcontainers
@TestPropertySource(
    properties = {
      "PLATFORM_BOOTSTRAP_CLIENT_ID=test-platform-client",
      "PLATFORM_BOOTSTRAP_CLIENT_SECRET=a-test-platform-secret"
    })
class SocialLoginIntegrationTest extends RedisBackedIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  private static HttpServer gitHubStub;
  private static final AtomicReference<StubGitHubUser> CURRENT_STUB_USER = new AtomicReference<>();

  @DynamicPropertySource
  static void gitHubProviderProperties(final DynamicPropertyRegistry registry) throws IOException {
    gitHubStub = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    gitHubStub.createContext(
        "/login/oauth/access_token",
        exchange ->
            respondJson(
                exchange,
                "{\"access_token\":\"gh-stub-access-token\",\"token_type\":\"bearer\","
                    + "\"scope\":\"read:user,user:email\"}"));
    gitHubStub.createContext(
        "/user",
        exchange -> {
          final StubGitHubUser user = CURRENT_STUB_USER.get();
          respondJson(exchange, "{\"id\":" + user.id() + ",\"login\":\"" + user.login() + "\"}");
        });
    gitHubStub.createContext(
        "/user/emails",
        exchange -> {
          final StubGitHubUser user = CURRENT_STUB_USER.get();
          respondJson(
              exchange,
              "[{\"email\":\"" + user.verifiedEmail() + "\",\"primary\":true,\"verified\":true}]");
        });
    gitHubStub.start();

    final String base = "http://localhost:" + gitHubStub.getAddress().getPort();
    registry.add(
        "spring.security.oauth2.client.provider.github.authorization-uri",
        () -> base + "/login/oauth/authorize");
    registry.add(
        "spring.security.oauth2.client.provider.github.token-uri",
        () -> base + "/login/oauth/access_token");
    registry.add(
        "spring.security.oauth2.client.provider.github.user-info-uri", () -> base + "/user");
    registry.add("spring.security.oauth2.client.provider.github.user-name-attribute", () -> "id");
    // GitHubVerifiedEmailUserService's own extra call — outside the provider block above since
    // it's a Clavaris-owned property, not a standard OAuth2ClientProperties field (see
    // application.yml's own comment on clavaris.oauth2.github.emails-uri for why).
    registry.add("clavaris.oauth2.github.emails-uri", () -> base + "/user/emails");
  }

  @AfterAll
  static void stopGitHubStub() {
    if (gitHubStub != null) {
      gitHubStub.stop(0);
    }
  }

  private static void respondJson(final HttpExchange exchange, final String body)
      throws IOException {
    final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  @Value("${local.server.port}")
  private int port;

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private MailSender mailSender;
  @Autowired private PlatformAccountRepository platformAccounts;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @BeforeEach
  void resetStubUser() {
    CURRENT_STUB_USER.set(new StubGitHubUser(0, "unset", "unset@example.test"));
  }

  @Test
  void aBrandNewGitHubSignInCreatesARealAccountAndARepeatSignInLogsBackIntoTheSameOne()
      throws Exception {
    final String platformToken =
        requestPlatformAccessToken(
            "platform:organizations:write platform:social-login-policy:write");
    final UUID organizationId = createOrganization(platformToken, "GitHub Co");
    enableGitHubSocialLogin(platformToken, organizationId);

    CURRENT_STUB_USER.set(new StubGitHubUser(990001, "octocat", "octocat@example.test"));

    // First sign-in: no existing SocialIdentity/Account for this provider+email — a brand-new
    // signup, ADR-0020's "linkBrandNewAccount" branch.
    final SessionClient firstDevice = new SessionClient();
    final String firstRedirect = completeGitHubLogin(firstDevice, organizationId);
    // endsWith, not isEqualTo: sendRedirect() resolves the controller's relative target to an
    // absolute URL (scheme+host included), same reasoning as completeGitHubLogin's own comment.
    assertThat(firstRedirect).endsWith("/o/" + organizationId + "/login?authenticated");

    final List<String> emails =
        jdbcTemplate.queryForList(
            "select email from accounts where organization_id = ?", String.class, organizationId);
    assertThat(emails).containsExactly("octocat@example.test");

    final List<String> providerUserIds =
        jdbcTemplate.queryForList(
            "select si.provider_user_id from social_identities si "
                + "join accounts a on a.id = si.account_id "
                + "where a.organization_id = ? and si.provider = 'GITHUB'",
            String.class,
            organizationId);
    assertThat(providerUserIds).containsExactly("990001");

    // Second sign-in, a different browser session, same GitHub identity: must resolve straight to
    // the already-linked SocialIdentity (the "returning" branch) — no second Account row, same
    // redirect target.
    final SessionClient secondDevice = new SessionClient();
    final String secondRedirect = completeGitHubLogin(secondDevice, organizationId);
    assertThat(secondRedirect).endsWith("/o/" + organizationId + "/login?authenticated");

    final Integer accountCount =
        jdbcTemplate.queryForObject(
            "select count(*) from accounts where organization_id = ?",
            Integer.class,
            organizationId);
    assertThat(accountCount)
        .as("the second sign-in must resolve to the same Account, never create a second one")
        .isEqualTo(1);
  }

  // SDE-III review, 2026-09-16 — real bug found live: behind docker-compose.prod.yml's own Caddy,
  // Spring Security's default OAuth2Login redirect-uri template ({baseUrl}/login/oauth2/code/{id})
  // computed its base URL from the raw request Caddy's own reverse_proxy sees (this test process's
  // own address, never the real external one a caller actually used) - a real "Sign in with
  // Google/GitHub" attempt behind Caddy failed at the provider's own consent screen with a
  // redirect_uri_mismatch. application.yml's own server.forward-headers-strategy: framework is the
  // fix (see that property's own comment for the full finding and why it's safe here); this proves
  // it actually works, not just that it's the documented Spring Boot mechanism for this - the same
  // "confirmed live, not assumed" bar every other real bug this session closed already met.
  @Test
  void theAuthorizationRedirectUriReflectsForwardedHeadersBehindAReverseProxy() throws Exception {
    final String platformToken =
        requestPlatformAccessToken(
            "platform:organizations:write platform:social-login-policy:write");
    final UUID organizationId = createOrganization(platformToken, "Behind A Proxy Co");
    enableGitHubSocialLogin(platformToken, organizationId);

    // Simulates exactly what Caddy's own reverse_proxy adds to every request it forwards - a real
    // external caller's own scheme/host, never this test process's own loopback address.
    final SessionClient client = new SessionClient();
    final HttpResponse<String> entry =
        client.getWithForwardedHeaders(
            baseUri("/o/" + organizationId + "/login/social/github"),
            "https",
            "clavaris.example.test");
    assertThat(entry.statusCode()).isEqualTo(302);
    final String authorizationRedirect = entry.headers().firstValue("Location").orElseThrow();
    // The fix already worked by this point - sendRedirect()'s own relative-to-absolute resolution
    // used the forwarded host above, so this Location is already
    // "https://clavaris.example.test/oauth2/authorization/github", not a real, connectable
    // address. Only the path is real; reissue the next hop against this test server's own actual
    // port, still carrying the same forwarded headers, exactly as Caddy would on every hop, not
    // only the first.
    final URI authorizationRedirectUri = URI.create(authorizationRedirect);
    assertThat(authorizationRedirectUri.getPath()).isEqualTo("/oauth2/authorization/github");

    final HttpResponse<String> toProvider =
        client.getWithForwardedHeaders(
            baseUri(authorizationRedirectUri.getPath()), "https", "clavaris.example.test");
    assertThat(toProvider.statusCode()).isEqualTo(302);
    final String providerUrl = toProvider.headers().firstValue("Location").orElseThrow();

    final String redirectUri = queryParam(providerUrl, "redirect_uri");
    assertThat(redirectUri)
        .as(
            "the redirect_uri Spring Security sends the provider must reflect the forwarded"
                + " scheme/host a real caller behind Caddy actually used, never this test"
                + " process's own loopback address")
        .isEqualTo("https://clavaris.example.test/login/oauth2/code/github");
  }

  @Test
  void aProviderTheOrganizationHasNotEnabledRedirectsWithAnErrorWithoutEverReachingTheProvider()
      throws Exception {
    final String platformToken = requestPlatformAccessToken("platform:organizations:write");
    final UUID organizationId = createOrganization(platformToken, "No Social Co");
    // Deliberately never calls enableGitHubSocialLogin — this Organization's social login stays
    // at its default (disabled, ADR-0020 Decision 3).

    final SessionClient client = new SessionClient();
    final HttpResponse<String> entry =
        client.get(baseUri("/o/" + organizationId + "/login/social/github"));

    assertThat(entry.statusCode()).isEqualTo(302);
    assertThat(entry.headers().firstValue("Location").orElseThrow())
        .contains("/o/" + organizationId + "/login?socialLoginError");

    final Integer accountCount =
        jdbcTemplate.queryForObject(
            "select count(*) from accounts where organization_id = ?",
            Integer.class,
            organizationId);
    assertThat(accountCount).isZero();
  }

  @Test
  void anExistingPasswordAccountRaisesAPendingLinkAndSendsAConfirmationEmailWithoutLoggingIn()
      throws Exception {
    final String platformToken =
        requestPlatformAccessToken(
            "platform:organizations:write platform:social-login-policy:write");
    final UUID organizationId = createOrganization(platformToken, "Existing Password Co");
    enableGitHubSocialLogin(platformToken, organizationId);
    registerAccount(organizationId, "already-here@example.test", "a-correct-password");

    CURRENT_STUB_USER.set(new StubGitHubUser(990002, "samename", "already-here@example.test"));

    final SessionClient client = new SessionClient();
    final String finalRedirect = completeGitHubLogin(client, organizationId);

    assertThat(finalRedirect)
        .endsWith("/o/" + organizationId + "/login/social/confirmation-required");
    verify(mailSender)
        .sendSocialLinkConfirmation(
            eq("already-here@example.test"),
            eq(new OrganizationId(organizationId)),
            eq(SocialProvider.GITHUB),
            any());

    // Never logged in on this request — BR-ID-09's whole point.
    final Integer socialIdentityCount =
        jdbcTemplate.queryForObject(
            "select count(*) from social_identities si join accounts a on a.id = si.account_id "
                + "where a.organization_id = ?",
            Integer.class,
            organizationId);
    assertThat(socialIdentityCount).isZero();
  }

  // Two social logins pending at once in two Organizations of the same browser used to resolve to
  // one of them: the callback URL is flat and carries no tenant, so the session cookie was guessed
  // from "whichever tenant session has a social login in progress". The tenant now travels in the
  // OAuth state, so each callback reads its own Organization's session.
  @Test
  void twoSocialLoginsPendingAtOnceInTwoTenantsEachCompleteInTheirOwnTenant() throws Exception {
    final String platformToken =
        requestPlatformAccessToken(
            "platform:organizations:write platform:social-login-policy:write");
    final UUID tenantA = createOrganization(platformToken, "Social Tenant A");
    final UUID tenantB = createOrganization(platformToken, "Social Tenant B");
    enableGitHubSocialLogin(platformToken, tenantA);
    enableGitHubSocialLogin(platformToken, tenantB);

    final SessionClient sharedBrowser = new SessionClient();
    final String stateA = beginGitHubLogin(sharedBrowser, "/o/" + tenantA);
    final String stateB = beginGitHubLogin(sharedBrowser, "/o/" + tenantB);

    CURRENT_STUB_USER.set(new StubGitHubUser(990101, "tenant-a-user", "a-user@example.test"));
    final String redirectA = finishGitHubLogin(sharedBrowser, stateA);
    CURRENT_STUB_USER.set(new StubGitHubUser(990102, "tenant-b-user", "b-user@example.test"));
    final String redirectB = finishGitHubLogin(sharedBrowser, stateB);

    assertThat(redirectA).endsWith("/o/" + tenantA + "/login?authenticated");
    assertThat(redirectB).endsWith("/o/" + tenantB + "/login?authenticated");
    assertThat(
            jdbcTemplate.queryForList(
                "select email from accounts where organization_id = ?", String.class, tenantA))
        .containsExactly("a-user@example.test");
    assertThat(
            jdbcTemplate.queryForList(
                "select email from accounts where organization_id = ?", String.class, tenantB))
        .containsExactly("b-user@example.test");
    assertThat(sharedBrowser.cookieNames())
        .contains(
            "SESSION_" + tenantA.toString().replace("-", ""),
            "SESSION_" + tenantB.toString().replace("-", ""));
  }

  // The platform's own social login used to land its authenticated session under SESSION (the flat
  // callback had no tenant to name a cookie after) while /platform/** reads PLATFORM_SESSION, so a
  // successful platform social login never reached the dashboard.
  @Test
  void aPlatformSocialLoginKeepsItsSessionUnderPlatformSession() throws Exception {
    CURRENT_STUB_USER.set(
        new StubGitHubUser(990201, "platform-user", "platform-social@example.test"));
    final SessionClient browser = new SessionClient();

    final String state = beginGitHubLogin(browser, "/platform");
    final String finalRedirect = finishGitHubLogin(browser, state);

    assertThat(finalRedirect).endsWith("/platform/dashboard");
    assertThat(browser.cookieNames()).contains("PLATFORM_SESSION").doesNotContain("SESSION");
    assertThat(browser.get(baseUri("/platform/dashboard")).statusCode())
        .as("the platform session written by the callback must authenticate /platform/**")
        .isEqualTo(200);
  }

  // TD-TEST-009 / TD-FUT-043: the whole connect-a-provider round trip against the GitHub stub, with
  // the real OAuth2 login filter in the middle. That filter replaces the security context with the
  // provider's token, so what must hold afterwards is that the person is still the password
  // Account they were, the provider identity is linked to it, and nobody was signed in as the
  // provider's user.
  @Test
  void aSignedInAccountCanConnectGitHubAndStaysTheSameAccount() throws Exception {
    final UUID organizationId = organizationWithGitHub();
    registerAccount(organizationId, "connector@example.test", "a-correct-password");
    final SessionClient browser = new SessionClient();
    signIn(browser, organizationId, "connector@example.test", "a-correct-password");
    CURRENT_STUB_USER.set(new StubGitHubUser(990777, "octo-connect", "unrelated@example.test"));

    final String callback = connectGitHub(browser, organizationId);

    assertThat(callback).endsWith("/o/" + organizationId + "/account/connections?connected");
    assertThat(
            jdbcTemplate.queryForList(
                "select a.email from social_identities si join accounts a on a.id = si.account_id"
                    + " where a.organization_id = ? and si.provider = 'GITHUB'"
                    + " and si.provider_user_id = '990777'",
                String.class,
                organizationId))
        .as("linked to the Account that asked, whatever email GitHub reports")
        .containsExactly("connector@example.test");
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from accounts where organization_id = ?",
                Integer.class,
                organizationId))
        .as("nobody was created or signed in as the provider's user")
        .isEqualTo(1);
    assertThat(browser.get(baseUri("/o/" + organizationId + "/account/profile")).statusCode())
        .as("still signed in as the same Account after the provider round trip")
        .isEqualTo(200);
    assertThat(browser.get(baseUri("/o/" + organizationId + "/account/connections")).body())
        .contains("GitHub");
  }

  @Test
  void aProviderIdentityAlreadyLinkedToAnotherAccountIsRefusedAndTheSessionSurvives()
      throws Exception {
    final UUID organizationId = organizationWithGitHub();
    registerAccount(organizationId, "first-owner@example.test", "a-correct-password");
    registerAccount(organizationId, "second-owner@example.test", "a-correct-password");
    CURRENT_STUB_USER.set(new StubGitHubUser(990888, "shared-gh", "shared@example.test"));
    final SessionClient first = new SessionClient();
    signIn(first, organizationId, "first-owner@example.test", "a-correct-password");
    assertThat(connectGitHub(first, organizationId)).endsWith("?connected");
    final SessionClient second = new SessionClient();
    signIn(second, organizationId, "second-owner@example.test", "a-correct-password");

    final String callback = connectGitHub(second, organizationId);

    assertThat(callback).endsWith("/o/" + organizationId + "/account/connections?alreadyLinked");
    assertThat(second.get(baseUri("/o/" + organizationId + "/account/profile")).statusCode())
        .as("a refused connect still leaves the person signed in as themselves")
        .isEqualTo(200);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from social_identities si join accounts a on a.id = si.account_id"
                    + " where a.organization_id = ? and si.provider_user_id = '990888'",
                Integer.class,
                organizationId))
        .isEqualTo(1);
  }

  // TD-FUT-053: an Account that only ever signs in with a provider has no password to type, so a
  // sign-in made moments ago is its proof. Driven for real: GitHub creates the Account, and the
  // very session that sign-in established changes the email.
  @Test
  void aProviderOnlyAccountCanChangeItsEmailRightAfterSigningIn() throws Exception {
    final UUID organizationId = organizationWithGitHub();
    CURRENT_STUB_USER.set(new StubGitHubUser(990999, "no-password", "provider-only@example.test"));
    final SessionClient browser = new SessionClient();
    assertThat(completeGitHubLogin(browser, organizationId)).endsWith("login?authenticated");
    final String page = browser.get(baseUri("/o/" + organizationId + "/account/email")).body();

    final HttpResponse<String> requested =
        browser.postForm(
            baseUri("/o/" + organizationId + "/account/email"),
            "_csrf=" + csrfOf(page) + "&newEmail=moved-provider@example.test&currentPassword=");

    assertThat(requested.statusCode())
        .as("body: %s", requested.body().replaceAll("(?s)<head>.*?</head>", ""))
        .isEqualTo(302);
    assertThat(requested.headers().firstValue("Location").orElseThrow())
        .contains("emailChangeRequested");
    verify(mailSender)
        .sendEmailChangeVerification(eq("moved-provider@example.test"), any(), anyString());
  }

  private UUID organizationWithGitHub() throws IOException, InterruptedException {
    final String platformToken =
        requestPlatformAccessToken(
            "platform:organizations:write platform:social-login-policy:write");
    final UUID organizationId = createOrganization(platformToken, "Connect Co");
    enableGitHubSocialLogin(platformToken, organizationId);
    return organizationId;
  }

  private void signIn(
      final SessionClient browser,
      final UUID organizationId,
      final String email,
      final String password)
      throws IOException, InterruptedException {
    final HttpResponse<String> form = browser.get(baseUri("/o/" + organizationId + "/login"));
    final HttpResponse<String> submitted =
        browser.postForm(
            baseUri("/o/" + organizationId + "/login"),
            "_csrf="
                + csrfOf(form.body())
                + "&email="
                + urlEncode(email)
                + "&password="
                + urlEncode(password));
    assertThat(submitted.statusCode()).as("sign-in redirects on success").isEqualTo(302);
  }

  /** The POST on the Connected accounts page, the hop to the provider and its callback. */
  private String connectGitHub(final SessionClient browser, final UUID organizationId)
      throws IOException, InterruptedException {
    final String page =
        browser.get(baseUri("/o/" + organizationId + "/account/connections")).body();
    final HttpResponse<String> connect =
        browser.postForm(
            baseUri("/o/" + organizationId + "/account/connections/github/connect"),
            "_csrf=" + csrfOf(page));
    assertThat(connect.statusCode()).isEqualTo(302);
    final String authorization = connect.headers().firstValue("Location").orElseThrow();
    assertThat(authorization).contains("/oauth2/authorization/github");
    final HttpResponse<String> toProvider = browser.get(URI.create(authorization));
    final String state =
        queryParam(toProvider.headers().firstValue("Location").orElseThrow(), "state");
    return finishGitHubLogin(browser, state);
  }

  private static String csrfOf(final String html) {
    final Matcher matcher = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(html);
    assertThat(matcher.find()).as("page must render a _csrf hidden input").isTrue();
    return matcher.group(1);
  }

  /**
   * Walks the entire redirect chain for one GitHub sign-in attempt on the given {@code client}'s
   * own session, returning the final redirect target's path.
   */
  private String completeGitHubLogin(final SessionClient client, final UUID organizationId)
      throws IOException, InterruptedException {
    return finishGitHubLogin(client, beginGitHubLogin(client, "/o/" + organizationId));
  }

  /**
   * The first half of a sign-in: the entry link under {@code tenantOrPlatformPath}, then the hop to
   * Spring Security's own authorization endpoint, which redirects to the provider. Returns the
   * {@code state} it minted, which is all the provider hands back on the callback.
   */
  private String beginGitHubLogin(final SessionClient client, final String tenantOrPlatformPath)
      throws IOException, InterruptedException {
    final HttpResponse<String> entry =
        client.get(baseUri(tenantOrPlatformPath + "/login/social/github"));
    assertThat(entry.statusCode()).isEqualTo(302);
    final String authorizationRedirect = entry.headers().firstValue("Location").orElseThrow();
    assertThat(authorizationRedirect).contains("/oauth2/authorization/github");

    // sendRedirect() resolves a relative Location to an absolute one per the servlet spec, so
    // authorizationRedirect (and the callback URL below) already carry the full scheme+host —
    // wrapping them in baseUri(...) again would double-prepend it.
    final HttpResponse<String> toProvider = client.get(URI.create(authorizationRedirect));
    assertThat(toProvider.statusCode())
        .as("toProvider headers=%s body=%s", toProvider.headers().map(), toProvider.body())
        .isEqualTo(302);
    final String providerUrl = toProvider.headers().firstValue("Location").orElseThrow();
    assertThat(providerUrl).contains("/login/oauth/authorize");
    final String state = queryParam(providerUrl, "state");
    assertThat(state).as("Spring Security must have minted a real state value").isNotBlank();
    return state;
  }

  /**
   * The provider's own redirect back, with the {@code state} it was given. Never actually calls the
   * stub authorization endpoint itself (no consent screen to simulate; only the callback's own
   * effects matter here).
   */
  private String finishGitHubLogin(final SessionClient client, final String state)
      throws IOException, InterruptedException {
    final HttpResponse<String> callback =
        client.get(
            URI.create(
                baseUri("/login/oauth2/code/github").toString()
                    + "?code=stub-authorization-code&state="
                    + urlEncode(state)));
    assertThat(callback.statusCode()).isEqualTo(302);
    return callback.headers().firstValue("Location").orElseThrow();
  }

  private String requestPlatformAccessToken(final String scope)
      throws IOException, InterruptedException {
    final String basicAuth =
        Base64.getEncoder()
            .encodeToString("test-platform-client:a-test-platform-secret".getBytes());
    final HttpClient httpClient = HttpClient.newHttpClient();
    final HttpRequest request =
        HttpRequest.newBuilder(baseUri("/oauth2/token"))
            .header("Authorization", "Basic " + basicAuth)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "grant_type=client_credentials&scope=" + urlEncode(scope)))
            .build();
    final HttpResponse<String> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    return objectMapper.readTree(response.body()).get("access_token").asString();
  }

  private UUID createOrganization(final String platformToken, final String name)
      throws IOException, InterruptedException {
    final HttpClient httpClient = HttpClient.newHttpClient();
    final HttpRequest request =
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
    final HttpResponse<String> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    return UUID.fromString(objectMapper.readTree(response.body()).get("id").asString());
  }

  // A real platform_accounts row, written directly through the repository rather than the full
  // /platform/register + /platform/verify-email HTTP flow — this suite never logs in as this
  // account, only needs its row to exist for CreateOrganizationService's own owner check. Code
  // review finding: this previously skipped JpaPlatformAccountRepository entirely via a raw
  // insert with no credential row at all, on the reasoning that the credential JPA requires would
  // just be "a second, unused table row" — true functionally, but structurally invalid per
  // BR-ID-02 (never zero auth methods), a real gap migration V20260830110000's own deferred
  // constraint trigger now catches live. Same "not a real hash, this test never logs in" pattern
  // CreateOrganizationIntegrationTest's own identical helper already establishes.
  private UUID registerAPlatformAccount() {
    final PlatformAccount account =
        PlatformAccount.register(new Email("owner-" + UUID.randomUUID() + "@example.test"));
    account.attachPasswordCredential("not-a-real-hash-this-test-never-logs-in");
    platformAccounts.save(account);
    return account.id().value();
  }

  private void enableGitHubSocialLogin(final String platformToken, final UUID organizationId)
      throws IOException, InterruptedException {
    final HttpClient httpClient = HttpClient.newHttpClient();
    final HttpRequest request =
        HttpRequest.newBuilder(
                baseUri("/api/v1/admin/organizations/" + organizationId + "/social-login-policy"))
            .header("Authorization", "Bearer " + platformToken)
            .header("Content-Type", "application/json")
            .PUT(
                HttpRequest.BodyPublishers.ofString(
                    "{\"enabled\":true,\"providers\":[\"GITHUB\"]}"))
            .build();
    final HttpResponse<Void> response =
        httpClient.send(request, HttpResponse.BodyHandlers.discarding());
    assertThat(response.statusCode()).isEqualTo(200);
  }

  // Same real-registration-form shortcut AuthorizationCodeFlowIntegrationTest's own registerAccount
  // helper uses — a plain unauthenticated POST, no CSRF token needed here since this suite never
  // needs the specific value, only that a real row (with a real password credential) exists.
  private void registerAccount(final UUID organizationId, final String email, final String password)
      throws IOException, InterruptedException {
    final HttpClient httpClient =
        HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    final HttpRequest getForm =
        HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/register")).GET().build();
    final HttpResponse<String> formResponse =
        httpClient.send(getForm, HttpResponse.BodyHandlers.ofString());
    final Matcher matcher =
        Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(formResponse.body());
    assertThat(matcher.find()).isTrue();
    final String csrfToken = matcher.group(1);

    final String body =
        "_csrf="
            + csrfToken
            + "&email="
            + urlEncode(email)
            + "&password="
            + urlEncode(password)
            + "&confirmPassword="
            + urlEncode(password);
    final HttpRequest register =
        HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/register"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    final HttpResponse<Void> response =
        httpClient.send(register, HttpResponse.BodyHandlers.discarding());
    assertThat(response.statusCode()).isEqualTo(302);
  }

  private URI baseUri(final String path) {
    return URI.create("http://localhost:" + port + path);
  }

  private static String urlEncode(final String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String queryParam(final String url, final String name) {
    final String query = URI.create(url).getRawQuery();
    for (final String pair : query.split("&")) {
      final int equalsIndex = pair.indexOf('=');
      if (equalsIndex >= 0 && pair.substring(0, equalsIndex).equals(name)) {
        return URLDecoder.decode(pair.substring(equalsIndex + 1), StandardCharsets.UTF_8);
      }
    }
    return null;
  }

  /**
   * One real browser session's worth of cookie state — a fresh instance per "device" a test needs
   * to simulate, mirroring how {@link AuthorizationCodeFlowIntegrationTest} keeps one shared {@link
   * CookieManager} for its own single-session flow.
   */
  private static final class SessionClient {
    private final CookieManager cookies = new CookieManager();
    private final HttpClient httpClient =
        HttpClient.newBuilder()
            .cookieHandler(cookies)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    Set<String> cookieNames() {
      return cookies.getCookieStore().getCookies().stream()
          .map(HttpCookie::getName)
          .collect(Collectors.toSet());
    }

    HttpResponse<String> get(final URI uri) throws IOException, InterruptedException {
      final HttpRequest request = HttpRequest.newBuilder(uri).GET().build();
      return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> postForm(final URI uri, final String body)
        throws IOException, InterruptedException {
      final HttpRequest request =
          HttpRequest.newBuilder(uri)
              .header("Content-Type", "application/x-www-form-urlencoded")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    // Simulates what Caddy's own reverse_proxy directive adds to every request it forwards, not
    // just a one-off test convenience — see
    // theAuthorizationRedirectUriReflectsForwardedHeadersBehindAReverseProxy's
    // own comment for the real bug this exists to prove closed.
    HttpResponse<String> getWithForwardedHeaders(
        final URI uri, final String forwardedProto, final String forwardedHost)
        throws IOException, InterruptedException {
      final HttpRequest request =
          HttpRequest.newBuilder(uri)
              .header("X-Forwarded-Proto", forwardedProto)
              .header("X-Forwarded-Host", forwardedHost)
              .GET()
              .build();
      return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
  }

  private record StubGitHubUser(long id, String login, String verifiedEmail) {}
}
