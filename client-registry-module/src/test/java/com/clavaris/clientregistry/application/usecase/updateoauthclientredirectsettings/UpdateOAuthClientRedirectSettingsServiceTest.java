package com.clavaris.clientregistry.application.usecase.updateoauthclientredirectsettings;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Same rationale/shape as {@code deactivateoauthclient.DeactivateOAuthClientServiceTest}. */
class UpdateOAuthClientRedirectSettingsServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformAccount(UUID.randomUUID());

  private final UUID organizationId = UUID.randomUUID();
  private OAuthClientRepository oauthClients;
  private AuditEventRecorder auditEvents;
  private UpdateOAuthClientRedirectSettingsService service;

  @BeforeEach
  void setUp() {
    oauthClients = mock(OAuthClientRepository.class);
    auditEvents = mock(AuditEventRecorder.class);
    service = new UpdateOAuthClientRedirectSettingsService(oauthClients, auditEvents);
  }

  private OAuthClient sampleClient() {
    return OAuthClient.register(
        organizationId,
        "target-client",
        "argon2id$hashed",
        List.of(),
        List.of("authorization_code", "refresh_token", "client_credentials"),
        List.of("openid", "profile", "email", "offline_access"),
        true,
        List.of());
  }

  @Test
  void savesTheClientWithTheNewRedirectSettings() {
    OAuthClient existing = sampleClient();
    when(oauthClients.findByClientId("target-client")).thenReturn(Optional.of(existing));

    service.handle(
        new UpdateOAuthClientRedirectSettingsCommand(
            "target-client",
            organizationId,
            List.of("https://jobseeker.example.com/callback"),
            List.of("https://jobseeker.example.com/logged-out"),
            ACTOR));

    verify(oauthClients)
        .save(
            argThat(
                saved ->
                    saved.redirectUris().equals(List.of("https://jobseeker.example.com/callback"))
                        && saved
                            .postLogoutRedirectUris()
                            .equals(List.of("https://jobseeker.example.com/logged-out"))));
  }

  @Test
  void leavesGrantTypesScopesAndConsentUntouched() {
    OAuthClient existing = sampleClient();
    when(oauthClients.findByClientId("target-client")).thenReturn(Optional.of(existing));

    service.handle(
        new UpdateOAuthClientRedirectSettingsCommand(
            "target-client",
            organizationId,
            List.of("https://jobseeker.example.com/callback"),
            List.of(),
            ACTOR));

    verify(oauthClients)
        .save(
            argThat(
                saved ->
                    saved.allowedGrantTypes().equals(existing.allowedGrantTypes())
                        && saved.allowedScopes().equals(existing.allowedScopes())
                        && saved.requireConsent() == existing.requireConsent()));
  }

  @Test
  void recordsAnAuditEvent() {
    OAuthClient existing = sampleClient();
    when(oauthClients.findByClientId("target-client")).thenReturn(Optional.of(existing));

    service.handle(
        new UpdateOAuthClientRedirectSettingsCommand(
            "target-client", organizationId, List.of(), List.of(), ACTOR));

    verify(auditEvents)
        .write(
            ACTOR,
            "oauth_client.redirect_settings_updated",
            "Organization",
            organizationId.toString(),
            "clientId=target-client");
  }

  @Test
  void rejectsAnUnknownClientIdWithoutPersistingOrRecordingAnything() {
    when(oauthClients.findByClientId("ghost-client")).thenReturn(Optional.empty());
    UpdateOAuthClientRedirectSettingsCommand command =
        new UpdateOAuthClientRedirectSettingsCommand(
            "ghost-client", organizationId, List.of(), List.of(), ACTOR);

    assertThatExceptionOfType(OAuthClientNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(oauthClients, never()).save(any());
    verifyNoInteractions(auditEvents);
  }

  // Same tenant-mismatch-collapses-to-404 reasoning DeactivateOAuthClientServiceTest's own
  // identical test documents.
  @Test
  void rejectsAClientThatBelongsToADifferentOrganizationWithoutPersistingOrRecordingAnything() {
    OAuthClient existing = sampleClient();
    when(oauthClients.findByClientId("target-client")).thenReturn(Optional.of(existing));
    UUID unrelatedOrganizationId = UUID.randomUUID();
    UpdateOAuthClientRedirectSettingsCommand command =
        new UpdateOAuthClientRedirectSettingsCommand(
            "target-client", unrelatedOrganizationId, List.of(), List.of(), ACTOR);

    assertThatExceptionOfType(OAuthClientNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(oauthClients, never()).save(any());
    verifyNoInteractions(auditEvents);
  }

  // Live UX bug fix, 2026-09-24: an inactive client's redirect settings must not be editable —
  // see OAuthClientInactiveException's own Javadoc.
  @Test
  void rejectsAnInactiveClientWithoutPersistingOrRecordingAnything() {
    OAuthClient existing = sampleClient().deactivate();
    when(oauthClients.findByClientId("target-client")).thenReturn(Optional.of(existing));
    UpdateOAuthClientRedirectSettingsCommand command =
        new UpdateOAuthClientRedirectSettingsCommand(
            "target-client",
            organizationId,
            List.of("https://jobseeker.example.com/callback"),
            List.of(),
            ACTOR);

    assertThatExceptionOfType(OAuthClientInactiveException.class)
        .isThrownBy(() -> service.handle(command));

    verify(oauthClients, never()).save(any());
    verifyNoInteractions(auditEvents);
  }

  @Test
  void rejectsAMalformedRedirectUriWithoutPersistingOrRecordingAnything() {
    OAuthClient existing = sampleClient();
    when(oauthClients.findByClientId("target-client")).thenReturn(Optional.of(existing));
    UpdateOAuthClientRedirectSettingsCommand command =
        new UpdateOAuthClientRedirectSettingsCommand(
            "target-client", organizationId, List.of("not a uri at all ::"), List.of(), ACTOR);

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> service.handle(command));

    verify(oauthClients, never()).save(any());
    verifyNoInteractions(auditEvents);
  }
}
