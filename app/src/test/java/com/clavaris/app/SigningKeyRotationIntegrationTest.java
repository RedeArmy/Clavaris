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
 * TD-SEC-008/ADR-0010 §5.2: the real, end-to-end proof that key rotation has genuine overlap — a
 * token signed under the pre-rotation key must still cryptographically verify against the
 * Organization's own JWKS response after rotation, not just that rotation itself returns 200. Same
 * "confirmed live, not assumed" bar as {@code OrganizationOidcIssuerIntegrationTest}, whose helper
 * methods this class deliberately mirrors rather than extracting a shared base — this codebase's
 * own existing convention across its integration test classes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(
    properties = {
      "PLATFORM_BOOTSTRAP_CLIENT_ID=test-platform-client",
      "PLATFORM_BOOTSTRAP_CLIENT_SECRET=a-test-platform-secret"
    })
class SigningKeyRotationIntegrationTest extends RedisBackedIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Value("${local.server.port}")
  private int port;

  @Autowired private PlatformAccountRepository platformAccounts;

  private final HttpClient httpClient = HttpClient.newHttpClient();
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void aTokenSignedBeforeRotationStillVerifiesAgainstJwksAfterRotation() throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID organizationId = createOrganization(platformToken, "Rotation Overlap Co");
    ClientCredentials client = registerOAuthClient(platformToken, organizationId);

    SignedJWT preRotationJwt =
        parse(accessTokenOf(requestOrganizationToken(organizationId, client)));
    String preRotationKid = preRotationJwt.getHeader().getKeyID();

    rotateSigningKey(platformToken, organizationId);

    JWKSet jwksAfterRotation = parseJwkSet(get("/o/" + organizationId + "/oauth2/jwks").body());
    JWK stillPublished = jwksAfterRotation.getKeyByKeyId(preRotationKid);
    assertThat(stillPublished)
        .as("TD-SEC-008: JWKS must keep publishing the retired key within the overlap window")
        .isNotNull();
    assertThat(verify(preRotationJwt, (RSAKey) stillPublished))
        .as("cryptographic proof the pre-rotation token is still verifiable, not just present")
        .isTrue();
  }

  @Test
  void aTokenIssuedAfterRotationIsSignedWithTheNewKidAndTheOldKidStillVerifiesAlongsideIt()
      throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID organizationId = createOrganization(platformToken, "Rotation New Key Co");
    ClientCredentials client = registerOAuthClient(platformToken, organizationId);
    String preRotationKid =
        parse(accessTokenOf(requestOrganizationToken(organizationId, client)))
            .getHeader()
            .getKeyID();

    rotateSigningKey(platformToken, organizationId);
    SignedJWT postRotationJwt =
        parse(accessTokenOf(requestOrganizationToken(organizationId, client)));
    String postRotationKid = postRotationJwt.getHeader().getKeyID();

    assertThat(postRotationKid)
        .as("a genuinely new key must sign tokens issued after rotation")
        .isNotEqualTo(preRotationKid);
    JWKSet jwks = parseJwkSet(get("/o/" + organizationId + "/oauth2/jwks").body());
    assertThat(jwks.getKeyByKeyId(preRotationKid))
        .as("both the retired and the new key must be published side by side during overlap")
        .isNotNull();
    assertThat(jwks.getKeyByKeyId(postRotationKid)).isNotNull();
    assertThat(verify(postRotationJwt, (RSAKey) jwks.getKeyByKeyId(postRotationKid))).isTrue();
  }

  @Test
  void rotatingATwoOrganizationsKeyNeverAffectsTheOther() throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID organizationA = createOrganization(platformToken, "Rotation Isolation A");
    UUID organizationB = createOrganization(platformToken, "Rotation Isolation B");
    ClientCredentials clientB = registerOAuthClient(platformToken, organizationB);
    String kidBBeforeRotatingA =
        parse(accessTokenOf(requestOrganizationToken(organizationB, clientB)))
            .getHeader()
            .getKeyID();

    rotateSigningKey(platformToken, organizationA);

    JWKSet jwksOfB = parseJwkSet(get("/o/" + organizationB + "/oauth2/jwks").body());
    assertThat(jwksOfB.getKeyByKeyId(kidBBeforeRotatingA))
        .as("rotating Organization A's key must never retire or otherwise touch B's own key")
        .isNotNull();
  }

  @Test
  void returns404WhenRotatingAnOrganizationThatDoesNotExist() throws Exception {
    String platformToken = requestPlatformAccessToken();

    HttpResponse<String> response = rotateSigningKey(platformToken, UUID.randomUUID());

    assertThat(response.statusCode()).isEqualTo(404);
  }

  @Test
  void rejectsRotationWithoutTheSigningKeysRotateScope() throws Exception {
    // requestPlatformAccessToken() below only asks for organizations:write — same defence-in-
    // depth proof AdminApiSecurityConfigTest-style tests already apply to the other admin scopes.
    String platformToken = requestPlatformAccessTokenWithoutRotateScope();
    UUID organizationId = createOrganization(platformToken, "No Rotate Scope Co");

    HttpResponse<String> response = rotateSigningKey(platformToken, organizationId);

    assertThat(response.statusCode()).isEqualTo(403);
  }

  private HttpResponse<String> rotateSigningKey(String platformToken, UUID organizationId)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(
                baseUri("/api/v1/admin/organizations/" + organizationId + "/signing-keys/rotate"))
            .header("Authorization", "Bearer " + platformToken)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private String accessTokenOf(HttpResponse<String> tokenResponse) {
    return objectMapper.readTree(tokenResponse.body()).get("access_token").asString();
  }

  private String requestPlatformAccessToken() throws IOException, InterruptedException {
    return requestPlatformAccessToken("platform:organizations:write platform:signing-keys:rotate");
  }

  private String requestPlatformAccessTokenWithoutRotateScope()
      throws IOException, InterruptedException {
    return requestPlatformAccessToken("platform:organizations:write");
  }

  private String requestPlatformAccessToken(String scope) throws IOException, InterruptedException {
    String basicAuth =
        Base64.getEncoder()
            .encodeToString("test-platform-client:a-test-platform-secret".getBytes());
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/oauth2/token"))
            .header("Authorization", "Basic " + basicAuth)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(
                HttpRequest.BodyPublishers.ofString("grant_type=client_credentials&scope=" + scope))
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

  // Same rationale as OrganizationOidcIssuerIntegrationTest's own identical helper — a real
  // PlatformAccount row written directly, not through the full register + verify-email HTTP flow.
  private UUID registerAPlatformAccount() {
    PlatformAccount account =
        PlatformAccount.register(new Email("owner-" + UUID.randomUUID() + "@example.test"));
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
      UUID organizationId, ClientCredentials client) throws IOException, InterruptedException {
    String basicAuth =
        Base64.getEncoder()
            .encodeToString((client.clientId() + ":" + client.clientSecret()).getBytes());
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
