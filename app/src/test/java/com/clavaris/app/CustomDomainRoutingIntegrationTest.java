package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.app.support.RedisBackedIntegrationTest;
import com.clavaris.app.support.TestMailSenderConfig;
import com.clavaris.clientregistry.application.usecase.requestclientdomainconfig.ClientDomainConfigRepository;
import com.clavaris.clientregistry.domain.model.ClientDomainConfig;
import com.clavaris.clientregistry.domain.model.ClientDomainMode;
import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.PlatformAccount;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * ADR-0009 §2/§4, end to end against the real running server: a request arriving on a verified
 * custom domain's own {@code Host} header — never Clavaris's own path-based {@code
 * /o/{organizationId}} convention — must still reach the right Organization's own {@code
 * /oauth2/authorize} chain (via {@code CustomDomainRequestRewriteFilter}'s internal forward), and
 * an unverified/absent domain configuration must never route anything.
 *
 * <p>Uses a raw socket, not the JDK's {@code HttpClient} — the {@code Host} header is on the JDK's
 * own restricted-header list and cannot be overridden through {@code HttpRequest} at all; this is
 * the standard escape hatch for genuinely needing to spoof it in a test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestMailSenderConfig.class)
@Testcontainers
@TestPropertySource(
    properties = {
      "PLATFORM_BOOTSTRAP_CLIENT_ID=test-platform-client",
      "PLATFORM_BOOTSTRAP_CLIENT_SECRET=a-test-platform-secret"
    })
class CustomDomainRoutingIntegrationTest extends RedisBackedIntegrationTest {

  private static final String REDIRECT_URI = "https://client.example.test/callback";

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Value("${local.server.port}")
  private int port;

  @Autowired private ClientDomainConfigRepository domainConfigs;
  @Autowired private PlatformAccountRepository platformAccounts;

  private final HttpClient httpClient =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void aVerifiedCustomDomainRoutesToItsOwnOrganizationsAuthorizeEndpoint() throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID organizationId = createOrganization(platformToken, "Custom Domain Co");
    RegisteredClient client = registerOAuthClient(platformToken, organizationId);
    String hostname = "login-" + UUID.randomUUID() + ".example.test";
    domainConfigs.save(
        ClientDomainConfig.request(client.id(), ClientDomainMode.CNAME, hostname, null)
            .markVerified());

    RawHttpResponse response =
        rawGet("/oauth2/authorize?" + authorizeQuery(client.clientId()), hostname);

    // Unauthenticated -> OrganizationLoginRedirectEntryPoint's own 302, scoped to THIS
    // Organization — proof the forward actually landed on the right tenant's own chain, not a
    // 404 (no matching chain) or the platform tier's own unrelated one.
    assertThat(response.statusCode()).isEqualTo(302);
    assertThat(response.headers().get("location"))
        .as("the rewrite must resolve the Host to this exact Organization, not any other")
        .contains("/o/" + organizationId + "/login");
  }

  @Test
  void anUnverifiedCustomDomainRoutesNowhere() throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID organizationId = createOrganization(platformToken, "Pending Domain Co");
    RegisteredClient client = registerOAuthClient(platformToken, organizationId);
    String hostname = "pending-" + UUID.randomUUID() + ".example.test";
    // Deliberately never marked verified — BR-CLIENT-04.
    domainConfigs.save(
        ClientDomainConfig.request(client.id(), ClientDomainMode.CNAME, hostname, null));

    RawHttpResponse response =
        rawGet("/oauth2/authorize?" + authorizeQuery(client.clientId()), hostname);

    // No rewrite happened, so this lands on whatever the bare (platform-tier) /oauth2/authorize
    // path resolves to on its own — never this Organization's own login page.
    assertThat(response.headers().getOrDefault("location", ""))
        .doesNotContain("/o/" + organizationId + "/login");
  }

  @Test
  void staticAssetsAreNeverRewrittenEvenOnAVerifiedCustomDomain() throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID organizationId = createOrganization(platformToken, "Static Asset Co");
    RegisteredClient client = registerOAuthClient(platformToken, organizationId);
    String hostname = "assets-" + UUID.randomUUID() + ".example.test";
    domainConfigs.save(
        ClientDomainConfig.request(client.id(), ClientDomainMode.CNAME, hostname, null)
            .markVerified());

    RawHttpResponse response = rawGet("/js/login-submit-guard.js", hostname);

    assertThat(response.statusCode())
        .as("a shared static asset must be served identically, not 404 under an /o/{id} prefix")
        .isEqualTo(200);
  }

  private String requestPlatformAccessToken() throws IOException, InterruptedException {
    String basicAuth =
        java.util.Base64.getEncoder()
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

  private UUID createOrganization(final String platformToken, final String name)
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
    JsonNode body = objectMapper.readTree(response.body());
    // Any failure here (e.g. a missing owner account) would surface as a null "id" — fail loudly
    // with the real response rather than a confusing downstream NPE.
    assertThat(body.get("id")).as("createOrganization response: " + response.body()).isNotNull();
    return UUID.fromString(body.get("id").asString());
  }

  private RegisteredClient registerOAuthClient(
      final String platformToken, final UUID organizationId)
      throws IOException, InterruptedException {
    List<String> allowedScopes = List.of("openid");
    String scopesJson =
        allowedScopes.stream().map(scope -> "\"" + scope + "\"").collect(Collectors.joining(","));
    String requestBody =
        """
        {
          "redirectUris": ["%s"],
          "allowedGrantTypes": ["authorization_code"],
          "allowedScopes": [%s],
          "requireConsent": false
        }
        """
            .formatted(REDIRECT_URI, scopesJson);
    HttpRequest request =
        HttpRequest.newBuilder(
                baseUri("/api/v1/admin/organizations/" + organizationId + "/clients"))
            .header("Authorization", "Bearer " + platformToken)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    JsonNode body = objectMapper.readTree(response.body());
    return new RegisteredClient(
        UUID.fromString(body.get("id").asString()), body.get("clientId").asString());
  }

  private record RegisteredClient(UUID id, String clientId) {}

  // OIDC's own mandatory params for /oauth2/authorize (response_type, client_id, redirect_uri,
  // scope) plus PKCE (ADR-0002: mandatory for every interactive flow, code_verifier is never
  // needed here since these tests never complete the exchange) — same shape
  // AuthorizationCodeFlowIntegrationTest's own getAuthorize helper already establishes; without
  // every one of these, Spring Authorization Server itself rejects the request with a 400 before
  // it ever reaches OrganizationLoginRedirectEntryPoint.
  private String authorizeQuery(final String clientId) {
    final byte[] verifierBytes = new byte[32];
    new java.security.SecureRandom().nextBytes(verifierBytes);
    final String codeVerifier =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(verifierBytes);
    final String codeChallenge;
    try {
      final java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
      final byte[] hash = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
      codeChallenge = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    } catch (final java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available on this JVM", e);
    }
    return "response_type=code"
        + "&client_id="
        + clientId
        + "&redirect_uri="
        + java.net.URLEncoder.encode(REDIRECT_URI, StandardCharsets.UTF_8)
        + "&scope=openid"
        + "&code_challenge="
        + codeChallenge
        + "&code_challenge_method=S256"
        + "&state=opaque-state-value";
  }

  private java.net.URI baseUri(final String path) {
    return java.net.URI.create("http://localhost:" + port + path);
  }

  // A real PlatformAccount row, written directly through the repository rather than the full
  // /platform/register + /platform/verify-email HTTP flow — same rationale and shape as
  // AuthorizationCodeFlowIntegrationTest's own identical helper: CreateOrganizationService
  // (security finding, SDE-III review, 2026-08-22) validates ownerPlatformAccountId against a
  // real row, so a bare random UUID would be rejected.
  private UUID registerAPlatformAccount() {
    PlatformAccount account =
        PlatformAccount.register(new Email("owner-" + UUID.randomUUID() + "@example.test"));
    account.attachPasswordCredential("not-a-real-hash-this-test-never-logs-in");
    platformAccounts.save(account);
    return account.id().value();
  }

  private RawHttpResponse rawGet(final String pathAndQuery, final String hostHeader)
      throws IOException {
    try (Socket socket = new Socket("localhost", port)) {
      socket.setSoTimeout(10_000);
      final OutputStream out = socket.getOutputStream();
      final String rawRequest =
          "GET "
              + pathAndQuery
              + " HTTP/1.1\r\n"
              + "Host: "
              + hostHeader
              + "\r\n"
              + "Connection: close\r\n\r\n";
      out.write(rawRequest.getBytes(StandardCharsets.US_ASCII));
      out.flush();
      final BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
      final String statusLine = reader.readLine();
      final int statusCode = Integer.parseInt(statusLine.split(" ")[1]);
      final Map<String, String> headers = new HashMap<>();
      String line;
      while ((line = reader.readLine()) != null && !line.isEmpty()) {
        final int colon = line.indexOf(':');
        if (colon > 0) {
          headers.put(
              line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
              line.substring(colon + 1).trim());
        }
      }
      return new RawHttpResponse(statusCode, headers);
    }
  }

  private record RawHttpResponse(int statusCode, Map<String, String> headers) {}
}
