package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.app.support.PlatformApiTestSupport;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * ADR-0043: PKCE as a per-client policy, against the running server. What the OpenID Foundation's
 * Basic OP plan needs (a confidential client that sends no PKCE) works for a client that chose
 * OPTIONAL and stays refused for the default, and choosing OPTIONAL never switches off a check on
 * what a request did send.
 *
 * <p>The authorization request is built here with or without a {@code code_challenge}, and the code
 * is exchanged with or without a {@code code_verifier}, so each case is one cell of the table the
 * ADR gives: policy by challenge by verifier.
 */
class ClientPkcePolicyIntegrationTest extends AbstractClientPkceIntegrationTest {

  // --- the default stays strict -------------------------------------------------------------

  @Test
  void aClientRegisteredWithoutAPolicyRequiresPkceAndRefusesARequestWithNoChallenge()
      throws Exception {
    Client client = clientWith(null, "default@example.test");

    String location = authorizeAndSignIn(client, null, "default@example.test");

    assertThat(location)
        .startsWith(REDIRECT_URI)
        .contains("error=invalid_request")
        .contains("code_challenge");
    assertThat(storedPolicy(client)).isEqualTo("REQUIRED");
  }

  @Test
  void aRequiredClientStillCompletesTheFlowWithAChallengeAndItsVerifier() throws Exception {
    Client client = clientWith("\"pkcePolicy\": \"REQUIRED\"", "required@example.test");
    String verifier = verifier();

    String location = authorizeAndSignIn(client, challenge(verifier), "required@example.test");

    assertThat(exchange(client, queryParam(location, "code"), verifier).statusCode())
        .isEqualTo(200);
  }

  // --- OPTIONAL: what changes ---------------------------------------------------------------

  @Test
  void anOptionalClientCompletesTheWholeFlowWithNoPkceAtAll() throws Exception {
    Client client = clientWith("\"pkcePolicy\": \"OPTIONAL\"", "optional@example.test");

    String location = authorizeAndSignIn(client, null, "optional@example.test");

    String code = queryParam(location, "code");
    assertThat(code).as("an authorization code, not an error: %s", location).isNotBlank();
    HttpResponse<String> token = exchange(client, code, null);
    assertThat(token.statusCode()).as(token.body()).isEqualTo(200);
    assertThat(json.readTree(token.body()).get("id_token")).isNotNull();
    assertThat(storedPolicy(client)).isEqualTo("OPTIONAL");
  }

  // --- OPTIONAL: what does not change ---------------------------------------------------------

  @Test
  void anOptionalClientThatDoesSendAChallengeStillMustProveItWithTheVerifier() throws Exception {
    Client client = clientWith("\"pkcePolicy\": \"OPTIONAL\"", "opt-wrong@example.test");
    String location = authorizeAndSignIn(client, challenge(verifier()), "opt-wrong@example.test");

    HttpResponse<String> token = exchange(client, queryParam(location, "code"), verifier());

    assertThat(token.statusCode()).isEqualTo(400);
    assertThat(token.body()).contains("invalid_grant");
  }

  @Test
  void anOptionalClientThatSendsAChallengeButNoVerifierIsRefused() throws Exception {
    Client client = clientWith("\"pkcePolicy\": \"OPTIONAL\"", "opt-none@example.test");
    String location = authorizeAndSignIn(client, challenge(verifier()), "opt-none@example.test");

    HttpResponse<String> token = exchange(client, queryParam(location, "code"), null);

    assertThat(token.statusCode()).isEqualTo(400);
  }

  @Test
  void aVerifierSentWithACodeThatHadNoChallengeIsADowngradeAndIsRefused() throws Exception {
    // RFC 9700 4.8: the code was issued with nothing to verify, so presenting a verifier must not
    // be
    // read as if PKCE had been in force. Spring Authorization Server refuses it itself, while it
    // authenticates the client (ClientSecretAuthenticationProvider), which is why this change adds
    // no
    // code of its own for it: this test is what keeps that true if the library or the way the
    // endpoint is wired ever changes.
    Client client = clientWith("\"pkcePolicy\": \"OPTIONAL\"", "downgrade@example.test");
    String location = authorizeAndSignIn(client, null, "downgrade@example.test");

    HttpResponse<String> token = exchange(client, queryParam(location, "code"), verifier());

    assertThat(token.statusCode()).isEqualTo(400);
    assertThat(token.body()).contains("code_challenge");
  }

  @Test
  void theSameDowngradeIsRefusedWhenTheSecretTravelsInTheBodyInsteadOfTheHeader() throws Exception {
    Client client = clientWith("\"pkcePolicy\": \"OPTIONAL\"", "downgrade-post@example.test");
    String code =
        queryParam(authorizeAndSignIn(client, null, "downgrade-post@example.test"), "code");

    HttpResponse<String> token =
        http.send(
            HttpRequest.newBuilder(uri("/o/" + client.organizationId() + "/oauth2/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "grant_type=authorization_code&code="
                            + code
                            + "&redirect_uri="
                            + enc(REDIRECT_URI)
                            + "&client_id="
                            + enc(client.clientId())
                            + "&client_secret="
                            + enc(client.clientSecret())
                            + "&code_verifier="
                            + verifier()))
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(token.statusCode()).isEqualTo(400);
    assertThat(token.body()).contains("code_challenge");
  }

  @Test
  void theChallengeMethodPlainIsStillRefusedWhateverThePolicy() throws Exception {
    Client client = clientWith("\"pkcePolicy\": \"OPTIONAL\"", "plain@example.test");
    String verifier = verifier();
    String query =
        "response_type=code&client_id="
            + client.clientId()
            + "&redirect_uri="
            + enc(REDIRECT_URI)
            + "&scope=openid&state=s&code_challenge="
            + verifier
            + "&code_challenge_method=plain";

    HttpResponse<Void> response =
        http.send(
            HttpRequest.newBuilder(
                    uri("/o/" + client.organizationId() + "/oauth2/authorize?" + query))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.discarding());

    String location = response.headers().firstValue("Location").orElseThrow();
    assertThat(location).startsWith(REDIRECT_URI).contains("error=invalid_request");
  }

  // --- the policy on the way in -----------------------------------------------------------------

  @Test
  void aPolicyThatIsNeitherValueIsRefusedAtRegistration() throws Exception {
    String platformToken =
        PlatformApiTestSupport.platformAccessToken(
            http, json, uri("/oauth2/token"), "platform:organizations:write");
    UUID organizationId = createOrganization(platformToken);

    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder(
                    uri("/api/v1/admin/organizations/" + organizationId + "/clients"))
                .header("Authorization", "Bearer " + platformToken)
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"redirectUris\":[\""
                            + REDIRECT_URI
                            + "\"],\"allowedGrantTypes\":"
                            + "[\"authorization_code\"],\"allowedScopes\":[\"openid\"],"
                            + "\"pkcePolicy\":\"NONE\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(400);
  }

  @Test
  void theRegistrationResponseAndTheAuditLogSayWhichPolicyAClientWasBornWith() throws Exception {
    Client client = clientWith("\"pkcePolicy\": \"OPTIONAL\"", "audit@example.test");

    String detail =
        jdbcTemplate.queryForObject(
            "SELECT detail FROM audit_events WHERE action = 'oauth_client.registered'"
                + " AND detail LIKE ? ORDER BY occurred_at DESC LIMIT 1",
            String.class,
            "%" + client.clientId() + "%");

    assertThat(detail).contains("pkcePolicy=OPTIONAL");
  }
}
