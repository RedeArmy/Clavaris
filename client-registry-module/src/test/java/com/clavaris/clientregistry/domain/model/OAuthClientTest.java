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
            List.of("openid", "profile"));

    assertThat(client.organizationId()).isEqualTo(organizationId);
    assertThat(client.clientId()).isEqualTo("jobseeker-web");
    assertThat(client.clientSecretHash()).isEqualTo("argon2id$hashed");
    assertThat(client.redirectUris()).containsExactly("https://jobseeker.example.com/callback");
    assertThat(client.allowedGrantTypes()).containsExactly("authorization_code", "refresh_token");
    assertThat(client.allowedScopes()).containsExactly("openid", "profile");
    assertThat(client.createdAt()).isNotNull();
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
                    List.of()));
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
                    List.of()));
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
            persistedCreatedAt);

    assertThat(client.id()).isEqualTo(persistedId);
    assertThat(client.createdAt()).isEqualTo(persistedCreatedAt);
  }
}
