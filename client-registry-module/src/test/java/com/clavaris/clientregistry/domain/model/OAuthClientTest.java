package com.clavaris.clientregistry.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OAuthClientTest {

  private final UUID organizationId = UUID.randomUUID();

  @Test
  void registerCarriesTheGivenFields() {
    OAuthClient client =
        OAuthClient.register(
            organizationId,
            "jobseeker-web",
            "argon2id$hashed",
            List.of("https://jobseeker.example.com/callback"),
            List.of("authorization_code", "refresh_token"),
            List.of("openid", "profile"),
            true,
            List.of());

    assertThat(client.organizationId()).isEqualTo(organizationId);
    assertThat(client.clientId()).isEqualTo("jobseeker-web");
    assertThat(client.clientSecretHash()).isEqualTo("argon2id$hashed");
    assertThat(client.redirectUris()).containsExactly("https://jobseeker.example.com/callback");
    assertThat(client.allowedGrantTypes()).containsExactly("authorization_code", "refresh_token");
    assertThat(client.allowedScopes()).containsExactly("openid", "profile");
    assertThat(client.requireConsent()).isTrue();
    assertThat(client.postLogoutRedirectUris()).isEmpty();
    assertThat(client.createdAt()).isNotNull();
  }

  @Test
  void registerCarriesRequireConsentFalseWhenExplicitlyOptedOut() {
    // TD-SEC-026/ADR-0017: a trusted first-party client's opt-out is an explicit false, not an
    // absence — this is that explicit value actually reaching the domain model.
    OAuthClient client =
        OAuthClient.register(
            organizationId,
            "trusted-client",
            "argon2id$hashed",
            List.of("https://trusted.example.com/callback"),
            List.of("authorization_code"),
            List.of("openid"),
            false,
            List.of());

    assertThat(client.requireConsent()).isFalse();
  }

  // TD-FUT-018: the real, load-bearing behavior — a present, non-empty allowlist actually reaches
  // the domain model, not just an absent/empty one.
  @Test
  void registerCarriesAnExplicitPostLogoutRedirectUri() {
    OAuthClient client =
        OAuthClient.register(
            organizationId,
            "jobseeker-web",
            "argon2id$hashed",
            List.of("https://jobseeker.example.com/callback"),
            List.of("authorization_code"),
            List.of("openid"),
            true,
            List.of("https://jobseeker.example.com/logged-out"));

    assertThat(client.postLogoutRedirectUris())
        .containsExactly("https://jobseeker.example.com/logged-out");
  }

  @Test
  void rejectsABlankClientId() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                OAuthClient.register(
                    organizationId,
                    " ",
                    "argon2id$hashed",
                    List.of("https://example.com/callback"),
                    List.of("authorization_code"),
                    List.of(),
                    true,
                    List.of()));
  }

  @Test
  void rejectsABlankSecretHash() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                OAuthClient.register(
                    organizationId,
                    "a-client",
                    " ",
                    List.of("https://example.com/callback"),
                    List.of("authorization_code"),
                    List.of(),
                    true,
                    List.of()));
  }

  @Test
  void rejectsNoRedirectUrisAtAll() {
    // BR-CLIENT-01's exact-match guarantee is meaningless with nothing to match against.
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                OAuthClient.register(
                    organizationId,
                    "a-client",
                    "argon2id$hashed",
                    List.of(),
                    List.of("authorization_code"),
                    List.of(),
                    true,
                    List.of()));
  }

  @Test
  void rejectsARelativeRedirectUri() {
    // BR-CLIENT-01: a relative URI can't be exact-matched against an absolute callback the
    // authorization server actually redirects to.
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                OAuthClient.register(
                    organizationId,
                    "a-client",
                    "argon2id$hashed",
                    List.of("/callback"),
                    List.of("authorization_code"),
                    List.of(),
                    true,
                    List.of()));
  }

  // SDE-III review, 2026-09-03 — real bug this test guards against: a plaintext http:// redirect
  // URI against an arbitrary network-reachable host used to be accepted, exposing the
  // authorization code to interception on the network path.
  @Test
  void rejectsAPlaintextHttpRedirectUriAgainstANonLoopbackHost() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                OAuthClient.register(
                    organizationId,
                    "a-client",
                    "argon2id$hashed",
                    List.of("http://example.com/callback"),
                    List.of("authorization_code"),
                    List.of(),
                    true,
                    List.of()));
  }

  // RFC 8252 §7.3: loopback HTTP is a native-app redirect, never network-interceptable — must
  // stay accepted, not collateral damage from the fix above.
  @Test
  void acceptsHttpRedirectUrisAgainstLocalhostAndLoopback() {
    OAuthClient localhostClient =
        OAuthClient.register(
            organizationId,
            "a-native-client",
            "argon2id$hashed",
            List.of("http://localhost:8080/callback"),
            List.of("authorization_code"),
            List.of(),
            true,
            List.of());
    OAuthClient loopbackIpClient =
        OAuthClient.register(
            organizationId,
            "another-native-client",
            "argon2id$hashed",
            List.of("http://127.0.0.1:8080/callback"),
            List.of("authorization_code"),
            List.of(),
            true,
            List.of());

    assertThat(localhostClient.redirectUris()).containsExactly("http://localhost:8080/callback");
    assertThat(loopbackIpClient.redirectUris()).containsExactly("http://127.0.0.1:8080/callback");
  }

  // This class's own Javadoc already scopes "web + mobile" clients as in-scope — a native app's
  // own custom scheme redirect is never network-interceptable to begin with, unlike plaintext
  // HTTP, and must stay accepted.
  @Test
  void acceptsACustomSchemeRedirectUriForANativeClient() {
    OAuthClient client =
        OAuthClient.register(
            organizationId,
            "a-mobile-client",
            "argon2id$hashed",
            List.of("com.example.app://callback"),
            List.of("authorization_code"),
            List.of(),
            true,
            List.of());

    assertThat(client.redirectUris()).containsExactly("com.example.app://callback");
  }

  @Test
  void rejectsAPlaintextHttpPostLogoutRedirectUriAgainstANonLoopbackHost() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                OAuthClient.register(
                    organizationId,
                    "a-client",
                    "argon2id$hashed",
                    List.of("https://example.com/callback"),
                    List.of("authorization_code"),
                    List.of(),
                    true,
                    List.of("http://example.com/logged-out")));
  }

  @Test
  void rejectsAMalformedRedirectUri() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                OAuthClient.register(
                    organizationId,
                    "a-client",
                    "argon2id$hashed",
                    List.of("not a uri at all ::"),
                    List.of("authorization_code"),
                    List.of(),
                    true,
                    List.of()));
  }

  @Test
  void rejectsNoGrantTypesAtAll() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                OAuthClient.register(
                    organizationId,
                    "a-client",
                    "argon2id$hashed",
                    List.of("https://example.com/callback"),
                    List.of(),
                    List.of(),
                    true,
                    List.of()));
  }

  // TD-FUT-018: same well-formed/absolute requirement as redirectUris, applied here — unlike
  // redirectUris, an empty list is valid (see registerCarriesTheGivenFields above); a malformed
  // entry never is.
  @Test
  void rejectsAMalformedPostLogoutRedirectUri() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                OAuthClient.register(
                    organizationId,
                    "a-client",
                    "argon2id$hashed",
                    List.of("https://example.com/callback"),
                    List.of("authorization_code"),
                    List.of(),
                    true,
                    List.of("not a uri at all ::")));
  }

  @Test
  void rejectsARelativePostLogoutRedirectUri() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                OAuthClient.register(
                    organizationId,
                    "a-client",
                    "argon2id$hashed",
                    List.of("https://example.com/callback"),
                    List.of("authorization_code"),
                    List.of(),
                    true,
                    List.of("/logged-out")));
  }

  @Test
  void reconstituteKeepsTheRealPersistedIdRatherThanMintingANewOne() {
    UUID persistedId = UUID.randomUUID();
    Instant persistedCreatedAt = Instant.parse("2026-01-01T00:00:00Z");

    OAuthClient client =
        OAuthClient.reconstitute(
            persistedId,
            organizationId,
            "a-client",
            "argon2id$hashed",
            List.of("https://example.com/callback"),
            List.of("authorization_code"),
            List.of("openid"),
            true,
            List.of(),
            persistedCreatedAt);

    assertThat(client.id()).isEqualTo(persistedId);
    assertThat(client.createdAt()).isEqualTo(persistedCreatedAt);
  }
}
