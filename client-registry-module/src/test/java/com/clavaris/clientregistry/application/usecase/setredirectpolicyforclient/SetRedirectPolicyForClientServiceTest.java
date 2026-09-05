package com.clavaris.clientregistry.application.usecase.setredirectpolicyforclient;

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
import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.clientregistry.domain.model.RedirectPolicy;
import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SetRedirectPolicyForClientServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformClient("test-client");
  private static final String REGISTERED_REDIRECT_URI = "https://app.example.com/callback";

  private OAuthClientRepository oauthClients;
  private RedirectPolicyRepository policies;
  private AuditEventRecorder auditEvents;
  private SetRedirectPolicyForClientService service;

  @BeforeEach
  void setUp() {
    oauthClients = mock(OAuthClientRepository.class);
    policies = mock(RedirectPolicyRepository.class);
    auditEvents = mock(AuditEventRecorder.class);
    service = new SetRedirectPolicyForClientService(oauthClients, policies, auditEvents);
  }

  private OAuthClient registeredClient(final UUID organizationId) {
    return OAuthClient.register(
        organizationId,
        "test_client",
        "hashed-secret",
        List.of(REGISTERED_REDIRECT_URI),
        List.of("authorization_code"),
        List.of("openid"),
        true,
        List.of());
  }

  @Test
  void definesAFreshPolicyWhenNoneExistsYet() {
    UUID organizationId = UUID.randomUUID();
    OAuthClient client = registeredClient(organizationId);
    when(oauthClients.findById(client.id())).thenReturn(Optional.of(client));
    when(policies.findByOAuthClientId(client.id())).thenReturn(Optional.empty());

    SetRedirectPolicyForClientResult result =
        service.handle(
            new SetRedirectPolicyForClientCommand(
                organizationId, client.id(), REGISTERED_REDIRECT_URI, null, null, null, ACTOR));

    assertThat(result.policy().oauthClientId()).isEqualTo(client.id());
    assertThat(result.policy().fallbackSignInRedirectUrl()).contains(REGISTERED_REDIRECT_URI);
    verify(policies).save(result.policy());
  }

  @Test
  void updatesAnExistingPolicyInPlaceRatherThanCreatingASecondOne() {
    UUID organizationId = UUID.randomUUID();
    OAuthClient client = registeredClient(organizationId);
    RedirectPolicy existing =
        RedirectPolicy.define(client.id(), REGISTERED_REDIRECT_URI, null, null, null);
    when(oauthClients.findById(client.id())).thenReturn(Optional.of(client));
    when(policies.findByOAuthClientId(client.id())).thenReturn(Optional.of(existing));

    SetRedirectPolicyForClientResult result =
        service.handle(
            new SetRedirectPolicyForClientCommand(
                organizationId, client.id(), null, REGISTERED_REDIRECT_URI, null, null, ACTOR));

    assertThat(result.policy().id())
        .as("re-tuning must update the same row, never mint a second one for the same client")
        .isEqualTo(existing.id());
    assertThat(result.policy().fallbackSignUpRedirectUrl()).contains(REGISTERED_REDIRECT_URI);
  }

  @Test
  void recordsAnAuditEventForTheChange() {
    UUID organizationId = UUID.randomUUID();
    OAuthClient client = registeredClient(organizationId);
    when(oauthClients.findById(client.id())).thenReturn(Optional.of(client));
    when(policies.findByOAuthClientId(client.id())).thenReturn(Optional.empty());

    service.handle(
        new SetRedirectPolicyForClientCommand(
            organizationId, client.id(), REGISTERED_REDIRECT_URI, null, null, null, ACTOR));

    verify(auditEvents)
        .write(
            eq(ACTOR),
            eq("redirect_policy.set"),
            eq("OAuthClient"),
            eq(client.id().toString()),
            any());
  }

  @Test
  void rejectsANonExistentOAuthClientWithoutPersistingAnything() {
    UUID organizationId = UUID.randomUUID();
    UUID nonExistentClientId = UUID.randomUUID();
    when(oauthClients.findById(nonExistentClientId)).thenReturn(Optional.empty());
    SetRedirectPolicyForClientCommand command =
        new SetRedirectPolicyForClientCommand(
            organizationId, nonExistentClientId, REGISTERED_REDIRECT_URI, null, null, null, ACTOR);

    assertThatExceptionOfType(OAuthClientNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(policies, never()).save(any());
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
    SetRedirectPolicyForClientCommand command =
        new SetRedirectPolicyForClientCommand(
            unrelatedOrganizationId, client.id(), REGISTERED_REDIRECT_URI, null, null, null, ACTOR);

    assertThatExceptionOfType(OAuthClientNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(policies, never()).save(any());
    verifyNoInteractions(auditEvents);
  }

  @Test
  void rejectsAUrlThatIsNotARegisteredRedirectUriWithoutPersistingAnything() {
    UUID organizationId = UUID.randomUUID();
    OAuthClient client = registeredClient(organizationId);
    when(oauthClients.findById(client.id())).thenReturn(Optional.of(client));
    SetRedirectPolicyForClientCommand command =
        new SetRedirectPolicyForClientCommand(
            organizationId,
            client.id(),
            "https://not-registered.example.com/callback",
            null,
            null,
            null,
            ACTOR);

    assertThatExceptionOfType(RedirectUrlNotRegisteredException.class)
        .isThrownBy(() -> service.handle(command));

    verify(policies, never()).save(any());
    verifyNoInteractions(auditEvents);
  }
}
