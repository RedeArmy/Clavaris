package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.app.support.RedisBackedIntegrationTest;
import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.PlatformAccount;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.ParseException;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * ADR-0010 §5, spike 0001's Appendix C addendum: the real per-Organization OIDC issuer — discovery,
 * JWKS, and the {@code client_credentials} token endpoint — served by one dynamic {@code
 * SecurityFilterChain} for every Organization, not a static per-tenant bean.
 *
 * <p>Same "confirmed live, not assumed" bar as {@code PlatformTokenIssuanceIntegrationTest}: a real
 * cryptographic signature check against the actually-published JWKS, not just a 200 status code —
 * plus the two guarantees that are new and specific to the dynamic single-chain design (addendum's
 * own residual-risk call-outs, not covered by the platform tier's single-tenant test at all):
 *
 * <ul>
 *   <li>two Organizations' JWKS documents never publish each other's key
 *   <li>a client registered under one Organization is rejected — not merely denied by scope, but
 *       treated as {@code invalid_client} — at a *different* Organization's token endpoint, even
 *       when presenting otherwise-valid credentials
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(
    properties = {
      "PLATFORM_BOOTSTRAP_CLIENT_ID=test-platform-client",
      "PLATFORM_BOOTSTRAP_CLIENT_SECRET=a-test-platform-secret"
    })
class OrganizationOidcIssuerIntegrationTest extends RedisBackedIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Value("${local.server.port}")
  private int port;

  @Autowired private PlatformAccountRepository platformAccounts;

  private final HttpClient httpClient = HttpClient.newHttpClient();
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void publishesADiscoveryDocumentScopedToTheOrganizationsOwnIssuer() throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID organizationId = createOrganization(platformToken, "Discovery Co");

    HttpResponse<String> response =
        get("/o/" + organizationId + "/.well-known/openid-configuration");

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode discovery = objectMapper.readTree(response.body());
    String expectedIssuer = "http://localhost:" + port + "/o/" + organizationId;
    assertThat(discovery.get("issuer").asString()).isEqualTo(expectedIssuer);
    assertThat(discovery.get("token_endpoint").asString())
        .isEqualTo(expectedIssuer + "/oauth2/token");
    assertThat(discovery.get("jwks_uri").asString()).isEqualTo(expectedIssuer + "/oauth2/jwks");
  }

  @Test
  void issuesATokenSignedWithTheKeyActuallyPublishedInTheOrganizationsOwnJwks() throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID organizationId = createOrganization(platformToken, "Signing Co");
    ClientCredentials client = registerOAuthClient(platformToken, organizationId);

    HttpResponse<String> tokenResponse =
        requestOrganizationToken(organizationId, client.clientId(), client.clientSecret());
    assertThat(tokenResponse.statusCode()).isEqualTo(200);

    JsonNode body = objectMapper.readTree(tokenResponse.body());
    SignedJWT jwt = parse(body.get("access_token").asString());
    String kid = jwt.getHeader().getKeyID();
    assertThat(kid).isNotBlank();
    assertThat(jwt.getJWTClaimsSet().getIssuer())
        .isEqualTo("http://localhost:" + port + "/o/" + organizationId);

    JWKSet jwkSet = parseJwkSet(get("/o/" + organizationId + "/oauth2/jwks").body());
    JWK publishedKey = jwkSet.getKeyByKeyId(kid);
    assertThat(publishedKey)
        .as(
            "the token's own kid must actually be present in this Organization's own published JWKS")
        .isNotNull();
    assertThat(verify(jwt, (RSAKey) publishedKey))
        .as("cryptographic proof, not just a matching kid")
        .isTrue();
  }

  @Test
  void neverPublishesOneOrganizationsKeyUnderAnothers() throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID organizationA = createOrganization(platformToken, "Org A");
    UUID organizationB = createOrganization(platformToken, "Org B");
    ClientCredentials clientA = registerOAuthClient(platformToken, organizationA);

    HttpResponse<String> tokenResponse =
        requestOrganizationToken(organizationA, clientA.clientId(), clientA.clientSecret());
    String kidA =
        parse(objectMapper.readTree(tokenResponse.body()).get("access_token").asString())
            .getHeader()
            .getKeyID();

    JWKSet jwksOfB = parseJwkSet(get("/o/" + organizationB + "/oauth2/jwks").body());
    assertThat(jwksOfB.getKeyByKeyId(kidA))
        .as("Organization B's JWKS must never serve Organization A's signing key")
        .isNull();
  }

  @Test
  void rejectsAClientRegisteredUnderADifferentOrganizationAsInvalidClient() throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID organizationA = createOrganization(platformToken, "Tenant A");
    UUID organizationB = createOrganization(platformToken, "Tenant B");
    ClientCredentials clientOfA = registerOAuthClient(platformToken, organizationA);

    // Valid credentials — just registered under the wrong Organization's token endpoint. ADR-0010
    // §5's "structurally impossible, not policy-disallowed" bar: ruled out explicitly by
    // OrganizationRegisteredClientRepository's own cross-tenant check, not by scope/consent denial.
    HttpResponse<String> response =
        requestOrganizationToken(organizationB, clientOfA.clientId(), clientOfA.clientSecret());

    assertThat(response.statusCode()).isEqualTo(401);
  }

  @Test
  void rejectsAWrongClientSecretAtTheOrganizationTokenEndpoint() throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID organizationId = createOrganization(platformToken, "Wrong Secret Co");
    ClientCredentials client = registerOAuthClient(platformToken, organizationId);

    HttpResponse<String> response =
        requestOrganizationToken(organizationId, client.clientId(), "not-the-real-secret");

    assertThat(response.statusCode()).isEqualTo(401);
  }

  @Test
  void doesNotShadowTheRegisterAccountFormUnderTheSameOrganizationPrefix() throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID organizationId = createOrganization(platformToken, "Register Form Co");

    // OrganizationAuthorizationServerConfig's securityMatcher is deliberately scoped to
    // /o/*/oauth2/**, /o/*/.well-known/**, /o/*/userinfo — this proves the sibling
    // /o/{organizationId}/register route (DefaultSecurityConfig's catch-all) is still reachable,
    // not shadowed into requiring authentication.
    HttpResponse<String> response = get("/o/" + organizationId + "/register");

    assertThat(response.statusCode()).isEqualTo(200);
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

  private ClientCredentials registerOAuthClient(String platformToken, UUID organizationId)
      throws IOException, InterruptedException {
    String requestBody =
        """
        {
          "redirectUris": ["https://example.test/callback"],
          "allowedGrantTypes": ["client_credentials"],
          "allowedScopes": ["test.read"]
        }
        """;
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

  private HttpResponse<String> requestOrganizationToken(
      UUID organizationId, String clientId, String clientSecret)
      throws IOException, InterruptedException {
    String basicAuth =
        Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes());
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/oauth2/token"))
            .header("Authorization", "Basic " + basicAuth)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "grant_type=client_credentials&scope=test.read"))
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> get(String path) throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder(baseUri(path)).GET().build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private URI baseUri(String path) {
    return URI.create("http://localhost:" + port + path);
  }

  private static SignedJWT parse(String accessToken) {
    try {
      return SignedJWT.parse(accessToken);
    } catch (ParseException e) {
      throw new IllegalStateException("Issued access token is not a parseable JWT", e);
    }
  }

  private static JWKSet parseJwkSet(String json) {
    try {
      return JWKSet.parse(json);
    } catch (ParseException e) {
      throw new IllegalStateException("Published JWKS document is not parseable", e);
    }
  }

  private static boolean verify(SignedJWT jwt, RSAKey publishedKey) {
    try {
      JWSVerifier verifier = new RSASSAVerifier(publishedKey.toRSAPublicKey());
      return jwt.verify(verifier);
    } catch (JOSEException e) {
      throw new IllegalStateException("Signature verification itself failed to run", e);
    }
  }

  private record ClientCredentials(String clientId, String clientSecret) {}
}
