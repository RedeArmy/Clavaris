package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.app.support.PlatformApiTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * ADR-0043: the server started with {@code clavaris.oauth.pkce.allow-optional=false}. Closing the
 * switch must hold for a client that was already stored as OPTIONAL, with no migration, and must
 * refuse a new one; and the counter that says who would have been affected must have seen the
 * request.
 */
@TestPropertySource(properties = "clavaris.oauth.pkce.allow-optional=false")
class ClientPkceSwitchIntegrationTest extends AbstractClientPkceIntegrationTest {

  @Autowired private MeterRegistry meters;

  @Test
  void aClientCannotBeRegisteredAsOptional() throws Exception {
    String platformToken =
        PlatformApiTestSupport.platformAccessToken(
            http, json, uri("/oauth2/token"), "platform:organizations:write");
    UUID organizationId = createOrganization(platformToken);

    HttpResponse<String> response =
        registerClient(platformToken, organizationId, "\"pkcePolicy\": \"OPTIONAL\"");

    assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
    assertThat(response.body()).contains("requires it for every client");
  }

  @Test
  void aStrictClientIsRegisteredAsAlways() throws Exception {
    Client client = clientWith("\"pkcePolicy\": \"REQUIRED\"", "strict@example.test");

    assertThat(storedPolicy(client)).isEqualTo("REQUIRED");
  }

  // The row says OPTIONAL, as it would have before the switch was closed. The server holds it to
  // PKCE anyway, and the stored value is left alone so reopening the switch restores it.
  @Test
  void aClientAlreadyStoredAsOptionalIsRefusedARequestWithNoChallenge() throws Exception {
    Client client = clientWith("\"pkcePolicy\": \"REQUIRED\"", "legacy@example.test");
    jdbcTemplate.update(
        "UPDATE oauth_clients SET pkce_policy = 'OPTIONAL' WHERE client_id = ?", client.clientId());

    String location = authorizeAndSignIn(client, null, "legacy@example.test");

    assertThat(location)
        .startsWith(REDIRECT_URI)
        .contains("error=invalid_request")
        .contains("code_challenge");
    assertThat(storedPolicy(client)).isEqualTo("OPTIONAL");
  }

  @Test
  void aClientAlreadyStoredAsOptionalStillCompletesTheFlowWithAChallenge() throws Exception {
    Client client = clientWith("\"pkcePolicy\": \"REQUIRED\"", "legacy2@example.test");
    jdbcTemplate.update(
        "UPDATE oauth_clients SET pkce_policy = 'OPTIONAL' WHERE client_id = ?", client.clientId());
    String verifier = verifier();

    String location = authorizeAndSignIn(client, challenge(verifier), "legacy2@example.test");

    assertThat(exchange(client, queryParam(location, "code"), verifier).statusCode())
        .isEqualTo(200);
  }

  @Test
  void theCounterSeesARequestWithoutAChallengeAndTagsItWithTheStoredPolicy() throws Exception {
    Client client = clientWith("\"pkcePolicy\": \"REQUIRED\"", "counted@example.test");
    jdbcTemplate.update(
        "UPDATE oauth_clients SET pkce_policy = 'OPTIONAL' WHERE client_id = ?", client.clientId());
    double before = count("absent", "OPTIONAL");

    authorizeAndSignIn(client, null, "counted@example.test");

    assertThat(count("absent", "OPTIONAL")).isGreaterThan(before);
  }

  private double count(final String pkce, final String policy) {
    var counter =
        meters
            .find("clavaris.oauth.authorize.requests")
            .tags("pkce", pkce, "policy", policy)
            .counter();
    return counter == null ? 0 : counter.count();
  }
}
