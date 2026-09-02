package com.clavaris.webhook.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WebhookEndpointTest {

  @Test
  void registerCarriesTheGivenFieldsAndStartsActiveWithNoPreviousSecret() {
    UUID organizationId = UUID.randomUUID();

    WebhookEndpoint endpoint =
        WebhookEndpoint.register(
            organizationId,
            "https://example.com/webhooks",
            "Production",
            List.of("account.created"),
            "encrypted-secret");

    assertThat(endpoint.organizationId()).isEqualTo(organizationId);
    assertThat(endpoint.url()).isEqualTo("https://example.com/webhooks");
    assertThat(endpoint.description()).isEqualTo("Production");
    assertThat(endpoint.subscribedEventTypes()).containsExactly("account.created");
    assertThat(endpoint.currentSecretEncrypted()).isEqualTo("encrypted-secret");
    assertThat(endpoint.previousSecretEncrypted()).isNull();
    assertThat(endpoint.active()).isTrue();
    assertThat(endpoint.createdAt()).isNotNull();
  }

  @Test
  void rejectsAPlainHttpUrl() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                WebhookEndpoint.register(
                    UUID.randomUUID(),
                    "http://example.com/webhooks",
                    null,
                    List.of("account.created"),
                    "secret"));
  }

  @Test
  void rejectsAMalformedUrl() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                WebhookEndpoint.register(
                    UUID.randomUUID(), "not a url", null, List.of("account.created"), "secret"));
  }

  @Test
  void rejectsEmptySubscribedEventTypes() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                WebhookEndpoint.register(
                    UUID.randomUUID(), "https://example.com", null, List.of(), "secret"));
  }

  @Test
  void subscribesToOnlyReturnsTrueForEventTypesActuallyRegistered() {
    WebhookEndpoint endpoint =
        WebhookEndpoint.register(
            UUID.randomUUID(),
            "https://example.com",
            null,
            List.of("account.created", "account.deleted"),
            "secret");

    assertThat(endpoint.subscribesTo("account.created")).isTrue();
    assertThat(endpoint.subscribesTo("account.suspended")).isFalse();
  }

  @Test
  void activeSecretsEncryptedReturnsOnlyCurrentBeforeAnyRotation() {
    WebhookEndpoint endpoint =
        WebhookEndpoint.register(
            UUID.randomUUID(), "https://example.com", null, List.of("x"), "current-secret");

    assertThat(endpoint.activeSecretsEncrypted(Instant.now())).containsExactly("current-secret");
  }

  @Test
  void rotateSecretKeepsThePreviousSecretActiveUntilTheOverlapWindowExpires() {
    WebhookEndpoint endpoint =
        WebhookEndpoint.register(
            UUID.randomUUID(), "https://example.com", null, List.of("x"), "old-secret");

    WebhookEndpoint rotated = endpoint.rotateSecret("new-secret", Duration.ofHours(24));

    assertThat(rotated.currentSecretEncrypted()).isEqualTo("new-secret");
    assertThat(rotated.previousSecretEncrypted()).isEqualTo("old-secret");
    // Current first, matching the Clavaris-Signature header's own ordering convention.
    assertThat(rotated.activeSecretsEncrypted(Instant.now()))
        .containsExactly("new-secret", "old-secret");
  }

  @Test
  void activeSecretsEncryptedDropsThePreviousSecretOnceTheOverlapWindowExpires() {
    WebhookEndpoint endpoint =
        WebhookEndpoint.register(
            UUID.randomUUID(), "https://example.com", null, List.of("x"), "old-secret");
    WebhookEndpoint rotated = endpoint.rotateSecret("new-secret", Duration.ofHours(24));

    List<String> afterExpiry =
        rotated.activeSecretsEncrypted(Instant.now().plus(Duration.ofHours(25)));

    assertThat(afterExpiry).containsExactly("new-secret");
  }

  @Test
  void deactivateThenActivateRoundTripsBackToTheOriginalActiveState() {
    WebhookEndpoint endpoint =
        WebhookEndpoint.register(
            UUID.randomUUID(), "https://example.com", null, List.of("x"), "secret");

    WebhookEndpoint deactivated = endpoint.deactivate();
    WebhookEndpoint reactivated = deactivated.activate();

    assertThat(deactivated.active()).isFalse();
    assertThat(reactivated.active()).isTrue();
    // Every other field survives the round trip unchanged.
    assertThat(reactivated.id()).isEqualTo(endpoint.id());
    assertThat(reactivated.currentSecretEncrypted()).isEqualTo(endpoint.currentSecretEncrypted());
  }

  @Test
  void reconstitutePreservesTheRealPersistedIdAndCreatedAt() {
    UUID id = UUID.randomUUID();
    Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");

    WebhookEndpoint endpoint =
        WebhookEndpoint.reconstitute(
            id,
            UUID.randomUUID(),
            "https://example.com",
            null,
            List.of("x"),
            "secret",
            null,
            null,
            true,
            createdAt);

    assertThat(endpoint.id()).isEqualTo(id);
    assertThat(endpoint.createdAt()).isEqualTo(createdAt);
  }
}
