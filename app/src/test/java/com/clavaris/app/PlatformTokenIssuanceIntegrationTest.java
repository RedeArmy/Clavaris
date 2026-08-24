package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.clavaris.app.support.RedisBackedIntegrationTest;
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
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * BR-PLATFORM-01, spike §6/test-strategy.md §3 (security-specific tier): "any real implementation's
 * test suite must assert actual signature verification against the published JWKS, per tenant, not
 * just endpoint reachability" — the spike's own §5.3 finding was a *silent* bug (issuance returned
 * 200, JWKS served the wrong key) that only a real signature check would have caught. This test
 * does the same thing against real code, not a hardcoded spike.
 *
 * <p>Also the first real exercise of {@code PlatformClientBootstrapRunner} and {@code
 * PlatformSigningKeyActivationRunner} end to end against a real Postgres. Plain JDK {@code
 * HttpClient}, not {@code TestRestTemplate}: confirmed live that {@code TestRestTemplate} is absent
 * from every Spring Boot 4.1.0 artifact entirely (not just relocated, like
 * {@code @WebMvcTest}/{@code @AutoConfigureMockMvc} turned out to be) —
 * {@code @Value("${local.server.port}")} is the oldest, most fundamental mechanism for this and the
 * least likely to have moved.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(
    properties = {
      "PLATFORM_BOOTSTRAP_CLIENT_ID=test-platform-client",
      "PLATFORM_BOOTSTRAP_CLIENT_SECRET=a-test-platform-secret"
    })
class PlatformTokenIssuanceIntegrationTest extends RedisBackedIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Value("${local.server.port}")
  private int port;

  // TD-SEC-019: application.yml's own dev-only fallback for clavaris.oauth2.token-hash-secret —
  // not overridden by this class's @TestPropertySource, so this is the exact secret the running
  // app is actually keying HashedTokenOAuth2AuthorizationService with.
  private static final String TOKEN_HASH_SECRET =
      "dev-only-oauth2-token-hash-secret-change-in-real-deployments";

  @Autowired private JdbcTemplate jdbcTemplate;

  private final HttpClient httpClient = HttpClient.newHttpClient();
  private final ObjectMapper objectMapper = new ObjectMapper();

  // TD-SEC-016: proves TokenIssuanceEventLogger actually fires wired into the real bean graph
  // (JwtGenerator.setJwtCustomizer(...) in PlatformAuthorizationServerConfig), not just that its
  // customize() method behaves correctly in isolation (TokenIssuanceEventLoggerTest covers that
  // separately) — the same "confirmed live, not assumed" bar every issuance test in this suite
  // already holds itself to for signature verification.
  private final ListAppender<ILoggingEvent> tokenIssuanceLogAppender = new ListAppender<>();

  // TD-SEC-017: /oauth2/revoke was, before this, completely unexercised anywhere in this codebase
  // — no test, no wiring investigated. This is the first real exercise of it, proving both that
  // revocation itself works end to end and that TokenRevocationEventLogger fires wired into the
  // real bean graph, not just in isolation (TokenRevocationEventLoggerTest covers that).
  private final ListAppender<ILoggingEvent> tokenRevocationLogAppender = new ListAppender<>();

  // One @BeforeEach/@AfterEach pair, not one per appender — attaching/detaching both together
  // keeps setup/teardown order obvious from a single method instead of relying on JUnit 5's
  // (unspecified, for same-annotation methods in one class) ordering across several.
  @BeforeEach
  void attachLogAppenders() {
    tokenIssuanceLogAppender.start();
    tokenIssuanceLogger().addAppender(tokenIssuanceLogAppender);
    tokenRevocationLogAppender.start();
    tokenRevocationLogger().addAppender(tokenRevocationLogAppender);
  }

  @AfterEach
  void detachLogAppenders() {
    tokenIssuanceLogger().detachAppender(tokenIssuanceLogAppender);
    tokenIssuanceLogAppender.stop();
    tokenIssuanceLogAppender.list.clear();
    tokenRevocationLogger().detachAppender(tokenRevocationLogAppender);
    tokenRevocationLogAppender.stop();
    tokenRevocationLogAppender.list.clear();
  }

  // By fully-qualified name, not TokenIssuanceEventLogger.class — that class is deliberately
  // package-private (same convention as every other class in app.infrastructure.config), and
  // Logback resolves loggers by name, so no import/visibility relaxation is needed to reach it.
  private static Logger tokenIssuanceLogger() {
    return (Logger)
        LoggerFactory.getLogger("com.clavaris.app.infrastructure.config.TokenIssuanceEventLogger");
  }

  private static Logger tokenRevocationLogger() {
    return (Logger)
        LoggerFactory.getLogger(
            "com.clavaris.app.infrastructure.config.TokenRevocationEventLogger");
  }

  @Test
  void issuesATokenSignedWithTheKeyActuallyPublishedInJwks() throws Exception {
    HttpResponse<String> tokenResponse =
        requestToken("test-platform-client", "a-test-platform-secret");

    assertThat(tokenResponse.statusCode()).isEqualTo(200);
    List<ILoggingEvent> tokenIssuedEvents = tokenIssuanceLogAppender.list;
    assertThat(tokenIssuedEvents)
        .as("TokenIssuanceEventLogger must actually fire on a real token exchange")
        .hasSize(1);
    assertThat(tokenIssuedEvents.get(0).getFormattedMessage())
        .contains("event=token_issued")
        .contains("tokenType=access_token")
        .contains("grantType=client_credentials")
        .contains("clientId=test-platform-client");
    JsonNode body = objectMapper.readTree(tokenResponse.body());
    String accessToken = body.get("access_token").asString();
    assertThat(accessToken).isNotBlank();

    SignedJWT jwt = parse(accessToken);
    String kid = jwt.getHeader().getKeyID();
    assertThat(kid).isNotBlank();
    assertThat(String.valueOf(jwt.getJWTClaimsSet().getClaim("scope")))
        .contains("platform:organizations:write");

    HttpResponse<String> jwksResponse = get("/oauth2/jwks");
    assertThat(jwksResponse.statusCode()).isEqualTo(200);
    JWKSet jwkSet = parseJwkSet(jwksResponse.body());
    JWK publishedKey = jwkSet.getKeyByKeyId(kid);
    assertThat(publishedKey)
        .as(
            "the token's own kid must actually be present in the published JWKS — the spike's §5.3 bug"
                + " served a real 200/valid-looking token signed with a DIFFERENT key than any kid ever"
                + " published, undetectable without this exact check")
        .isNotNull();

    boolean verified = verify(jwt, (RSAKey) publishedKey);
    assertThat(verified)
        .as(
            "cryptographic proof, not just a matching kid — the token must actually verify against the published key")
        .isTrue();
  }

  @Test
  void revokesARealTokenAndLogsAStructuredEventWithoutEverLoggingTheTokenItself() throws Exception {
    HttpResponse<String> tokenResponse =
        requestToken("test-platform-client", "a-test-platform-secret");
    String accessToken = objectMapper.readTree(tokenResponse.body()).get("access_token").asString();
    tokenRevocationLogAppender.list.clear(); // isolate from the issuance above

    HttpResponse<String> revokeResponse = revokeToken(accessToken);

    // RFC 7009 §2.2: 200 is the correct, spec-mandated response for a successful revocation
    // request — proves the endpoint itself works, which nothing in this codebase had verified
    // before this test existed.
    assertThat(revokeResponse.statusCode()).isEqualTo(200);
    assertThat(tokenRevocationLogAppender.list)
        .as("TokenRevocationEventLogger must actually fire on a real revocation request")
        .hasSize(1);
    assertThat(tokenRevocationLogAppender.list.get(0).getFormattedMessage())
        .contains("event=token_revoked")
        .contains("clientId=test-platform-client")
        .doesNotContain(accessToken);
  }

  @Test
  void rejectsAWrongClientSecret() throws Exception {
    HttpResponse<String> response = requestToken("test-platform-client", "the-wrong-secret");

    assertThat(response.statusCode()).isEqualTo(401);
  }

  @Test
  void storesTheAccessTokenValueHashedAtRestNeverInPlaintext() throws Exception {
    // TD-SEC-019: the real proof this row exists to give — a Postgres compromise/backup leak
    // must never hand over a directly-usable bearer credential. Computes the expected HMAC
    // independently (javax.crypto.Mac, not BearerTokenHasher itself — that class is package-
    // private to infrastructure.config and unreachable from this package, and duplicating the
    // computation here also avoids a tautological test that would pass even if
    // BearerTokenHasher itself were broken).
    HttpResponse<String> tokenResponse =
        requestToken("test-platform-client", "a-test-platform-secret");
    String accessToken = objectMapper.readTree(tokenResponse.body()).get("access_token").asString();
    String expectedHashedValue = hmacSha256Hex(TOKEN_HASH_SECRET, accessToken);

    List<String> rowsMatchingTheRawToken =
        jdbcTemplate.queryForList(
            "SELECT id FROM oauth2_authorization WHERE access_token_value = ?",
            String.class,
            accessToken);
    List<String> rowsMatchingTheExpectedHash =
        jdbcTemplate.queryForList(
            "SELECT id FROM oauth2_authorization WHERE access_token_value = ?",
            String.class,
            expectedHashedValue);

    assertThat(rowsMatchingTheRawToken)
        .as("the raw, directly-usable token value must never appear in oauth2_authorization")
        .isEmpty();
    assertThat(rowsMatchingTheExpectedHash)
        .as("exactly the HMAC-SHA256 digest of the issued token must be what was actually stored")
        .hasSize(1);
  }

  private static String hmacSha256Hex(String secret, String value) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void adminApiRejectsARequestWithNoToken() throws Exception {
    // BR-PLATFORM-02: no admin endpoint exists yet to call (CreateOrganization is a separate
    // slice) — this only proves the resource-server chain itself actually rejects an
    // unauthenticated request to the protected path prefix, not that a specific endpoint works.
    HttpResponse<String> response = get("/api/v1/admin/organizations");

    assertThat(response.statusCode()).isEqualTo(401);
  }

  private HttpResponse<String> requestToken(String clientId, String clientSecret)
      throws IOException, InterruptedException {
    String basicAuth =
        Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes());
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/oauth2/token"))
            .header("Authorization", "Basic " + basicAuth)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "grant_type=client_credentials&scope=platform:organizations:write"))
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> revokeToken(String token) throws IOException, InterruptedException {
    String basicAuth =
        Base64.getEncoder()
            .encodeToString("test-platform-client:a-test-platform-secret".getBytes());
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/oauth2/revoke"))
            .header("Authorization", "Basic " + basicAuth)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "token=" + token + "&token_type_hint=access_token"))
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
}
