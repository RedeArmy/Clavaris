package com.clavaris.clientregistry.application.usecase.setclientbranding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.domain.model.ClientBranding;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SetClientBrandingServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformClient("test-client");

  private OAuthClientRepository oauthClients;
  private ClientBrandingRepository brandings;
  private AuditEventRecorder auditEvents;
  private SetClientBrandingService service;

  @BeforeEach
  void setUp() {
    oauthClients = mock(OAuthClientRepository.class);
    brandings = mock(ClientBrandingRepository.class);
    auditEvents = mock(AuditEventRecorder.class);
    service = new SetClientBrandingService(oauthClients, brandings, auditEvents);
  }

  private OAuthClient registeredClient(final UUID organizationId) {
    return OAuthClient.register(
        organizationId,
        "test_client",
        "hashed-secret",
        List.of("https://app.example.com/callback"),
        List.of("authorization_code"),
        List.of("openid"),
        true,
        List.of());
  }

  @Test
  void definesFreshBrandingWhenNoneExistsYet() {
    UUID organizationId = UUID.randomUUID();
    OAuthClient client = registeredClient(organizationId);
    when(oauthClients.findById(client.id())).thenReturn(Optional.of(client));
    when(brandings.findByOAuthClientId(client.id())).thenReturn(Optional.empty());

    SetClientBrandingResult result =
        service.handle(
            new SetClientBrandingCommand(
                organizationId,
                client.id(),
                "https://cdn.example.com/logo.png",
                "#336699",
                "JobSeeker",
                ACTOR));

    assertThat(result.branding().oauthClientId()).isEqualTo(client.id());
    assertThat(result.branding().logoUrl()).contains("https://cdn.example.com/logo.png");
    assertThat(result.branding().applicationDisplayName()).contains("JobSeeker");
    verify(brandings).save(result.branding());
  }

  @Test
  void updatesExistingBrandingInPlaceRatherThanCreatingASecondRow() {
    UUID organizationId = UUID.randomUUID();
    OAuthClient client = registeredClient(organizationId);
    ClientBranding existing = ClientBranding.define(client.id(), null, "#111111", null);
    when(oauthClients.findById(client.id())).thenReturn(Optional.of(client));
    when(brandings.findByOAuthClientId(client.id())).thenReturn(Optional.of(existing));

    SetClientBrandingResult result =
        service.handle(
            new SetClientBrandingCommand(
                organizationId, client.id(), null, "#222222", null, ACTOR));

    assertThat(result.branding().id())
        .as("re-tuning must update the same row, never mint a second one for the same client")
        .isEqualTo(existing.id());
    assertThat(result.branding().primaryColor()).contains("#222222");
  }

  @Test
  void recordsAnAuditEventForTheChange() {
    UUID organizationId = UUID.randomUUID();
    OAuthClient client = registeredClient(organizationId);
    when(oauthClients.findById(client.id())).thenReturn(Optional.of(client));
    when(brandings.findByOAuthClientId(client.id())).thenReturn(Optional.empty());

    service.handle(
        new SetClientBrandingCommand(organizationId, client.id(), null, "#336699", null, ACTOR));

    verify(auditEvents)
        .write(
            eq(ACTOR),
            eq("client_branding.set"),
            eq("OAuthClient"),
            eq(client.id().toString()),
            any());
  }

  @Test
  void rejectsANonExistentOAuthClientWithoutPersistingAnything() {
    UUID organizationId = UUID.randomUUID();
    UUID nonExistentClientId = UUID.randomUUID();
    when(oauthClients.findById(nonExistentClientId)).thenReturn(Optional.empty());
    SetClientBrandingCommand command =
        new SetClientBrandingCommand(
            organizationId, nonExistentClientId, null, "#336699", null, ACTOR);

    assertThatExceptionOfType(OAuthClientNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(brandings, never()).save(any());
    verifyNoInteractions(auditEvents);
  }

  // BR-ORG-02-style cross-tenant defence in depth: a client that genuinely exists, but under a
  // different Organization than the path claims, must collapse into the same 404 a truly missing
  // id would produce — see OAuthClientNotFoundException's own Javadoc.
  @Test
  void rejectsAClientThatBelongsToADifferentOrganizationWithoutPersistingAnything() {
    OAuthClient client = registeredClient(UUID.randomUUID());
    UUID unrelatedOrganizationId = UUID.randomUUID();
    when(oauthClients.findById(client.id())).thenReturn(Optional.of(client));
    SetClientBrandingCommand command =
        new SetClientBrandingCommand(
            unrelatedOrganizationId, client.id(), null, "#336699", null, ACTOR);

    assertThatExceptionOfType(OAuthClientNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(brandings, never()).save(any());
    verifyNoInteractions(auditEvents);
  }
}
