package com.clavaris.webhook.application.usecase.updatewebhookendpointurl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.UnsafeWebhookUrlException;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointNotFoundException;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointRepository;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookUrlSsrfGuard;
import com.clavaris.webhook.domain.model.WebhookEndpoint;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UpdateWebhookEndpointUrlServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformClient("test-client");

  private final WebhookEndpointRepository endpoints = mock(WebhookEndpointRepository.class);
  private final WebhookUrlSsrfGuard ssrfGuard = mock(WebhookUrlSsrfGuard.class);
  private final AuditEventRecorder auditEvents = mock(AuditEventRecorder.class);
  private final UpdateWebhookEndpointUrlService service =
      new UpdateWebhookEndpointUrlService(endpoints, ssrfGuard, auditEvents);

  @Test
  void updatesTheUrlAfterPassingTheSsrfGuardAndAuditsIt() {
    WebhookEndpoint existing =
        WebhookEndpoint.register(
            UUID.randomUUID(), "https://old.example.com", null, List.of("x"), "secret");
    when(endpoints.findById(existing.id())).thenReturn(Optional.of(existing));

    WebhookEndpoint result =
        service.handle(
            new UpdateWebhookEndpointUrlCommand(existing.id(), "https://new.example.com", ACTOR));

    assertThat(result.url()).isEqualTo("https://new.example.com");
    verify(ssrfGuard).requireSafeToRegister("https://new.example.com");
    verify(endpoints).save(result);
    verify(auditEvents)
        .write(
            ACTOR,
            "webhook_endpoint.url_updated",
            "WebhookEndpoint",
            existing.id().toString(),
            null);
  }

  @Test
  void rejectsAnUnsafeUrlAndNeverSaves() {
    WebhookEndpoint existing =
        WebhookEndpoint.register(
            UUID.randomUUID(), "https://old.example.com", null, List.of("x"), "secret");
    when(endpoints.findById(existing.id())).thenReturn(Optional.of(existing));
    UnsafeWebhookUrlException ssrfRejection = new UnsafeWebhookUrlException("private address");
    doThrow(ssrfRejection).when(ssrfGuard).requireSafeToRegister("https://internal.example.com");
    UpdateWebhookEndpointUrlCommand command =
        new UpdateWebhookEndpointUrlCommand(existing.id(), "https://internal.example.com", ACTOR);

    assertThatExceptionOfType(UnsafeWebhookUrlException.class)
        .isThrownBy(() -> service.handle(command));
    verify(endpoints, never()).save(any());
  }

  @Test
  void rejectsUpdatingAnUnknownEndpoint() {
    UUID unknownId = UUID.randomUUID();
    when(endpoints.findById(unknownId)).thenReturn(Optional.empty());
    UpdateWebhookEndpointUrlCommand command =
        new UpdateWebhookEndpointUrlCommand(unknownId, "https://example.com", ACTOR);

    assertThatExceptionOfType(WebhookEndpointNotFoundException.class)
        .isThrownBy(() -> service.handle(command));
  }
}
