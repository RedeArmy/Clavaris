package com.clavaris.webhook.application.usecase.registerwebhookendpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.webhook.domain.model.WebhookEndpoint;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RegisterWebhookEndpointServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformClient("test-client");

  private final WebhookEndpointRepository endpoints = mock(WebhookEndpointRepository.class);
  private final OrganizationExistsChecker orgExistsChecker = mock(OrganizationExistsChecker.class);
  private final WebhookSigningSecretCipher cipher = mock(WebhookSigningSecretCipher.class);
  private final AuditEventRecorder auditEvents = mock(AuditEventRecorder.class);
  private final RegisterWebhookEndpointService service =
      new RegisterWebhookEndpointService(endpoints, orgExistsChecker, cipher, auditEvents);

  @Test
  void registersAnEndpointWithAnEncryptedRandomSecretAndReturnsTheRawOneExactlyOnce() {
    UUID organizationId = UUID.randomUUID();
    when(orgExistsChecker.exists(organizationId)).thenReturn(true);
    when(cipher.encrypt(any())).thenReturn("encrypted-secret");

    RegisterWebhookEndpointResult result =
        service.handle(
            new RegisterWebhookEndpointCommand(
                organizationId,
                "https://example.com/hooks",
                "Prod",
                List.of("account.created"),
                ACTOR));

    assertThat(result.endpoint().organizationId()).isEqualTo(organizationId);
    assertThat(result.endpoint().currentSecretEncrypted()).isEqualTo("encrypted-secret");
    assertThat(result.rawSigningSecret()).isNotBlank();
    verify(endpoints).save(result.endpoint());
    verify(auditEvents)
        .write(
            eq(ACTOR),
            eq("webhook_endpoint.registered"),
            eq("WebhookEndpoint"),
            eq(result.endpoint().id().toString()),
            any());
  }

  @Test
  void generatesADifferentRawSecretOnEveryCall() {
    UUID organizationId = UUID.randomUUID();
    when(orgExistsChecker.exists(organizationId)).thenReturn(true);
    when(cipher.encrypt(any())).thenAnswer(invocation -> "encrypted:" + invocation.getArgument(0));

    RegisterWebhookEndpointResult first =
        service.handle(
            new RegisterWebhookEndpointCommand(
                organizationId, "https://example.com/a", null, List.of("x"), ACTOR));
    RegisterWebhookEndpointResult second =
        service.handle(
            new RegisterWebhookEndpointCommand(
                organizationId, "https://example.com/b", null, List.of("x"), ACTOR));

    assertThat(first.rawSigningSecret()).isNotEqualTo(second.rawSigningSecret());
  }

  @Test
  void rejectsRegistrationUnderAnUnknownOrganizationWithoutSavingAnything() {
    UUID organizationId = UUID.randomUUID();
    when(orgExistsChecker.exists(organizationId)).thenReturn(false);
    RegisterWebhookEndpointCommand command =
        new RegisterWebhookEndpointCommand(
            organizationId, "https://example.com", null, List.of("x"), ACTOR);

    assertThatExceptionOfType(OrganizationNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(endpoints, never()).save(any());
    verifyNoInteractions(auditEvents);
  }

  @Test
  void neverPassesTheRawSecretToTheRepositoryOnlyTheEncryptedOne() {
    UUID organizationId = UUID.randomUUID();
    when(orgExistsChecker.exists(organizationId)).thenReturn(true);
    when(cipher.encrypt(any())).thenReturn("encrypted-secret");

    service.handle(
        new RegisterWebhookEndpointCommand(
            organizationId, "https://example.com", null, List.of("x"), ACTOR));

    WebhookEndpoint saved = captureSavedEndpoint();
    assertThat(saved.currentSecretEncrypted()).isEqualTo("encrypted-secret");
  }

  private WebhookEndpoint captureSavedEndpoint() {
    org.mockito.ArgumentCaptor<WebhookEndpoint> captor =
        org.mockito.ArgumentCaptor.forClass(WebhookEndpoint.class);
    verify(endpoints).save(captor.capture());
    return captor.getValue();
  }
}
