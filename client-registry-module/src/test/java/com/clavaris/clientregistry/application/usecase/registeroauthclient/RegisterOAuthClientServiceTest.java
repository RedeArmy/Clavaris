package com.clavaris.clientregistry.application.usecase.registeroauthclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.clientregistry.application.usecase.bootstrapplatformclient.ClientSecretHasher;
import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RegisterOAuthClientServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformClient("test-platform-client");

  private final UUID organizationId = UUID.randomUUID();
  private OAuthClientRepository oauthClients;
  private OrganizationExistsChecker organizationExistsChecker;
  private OrganizationEnvironmentChecker environmentChecker;
  private ClientSecretHasher hasher;
  // A real, simple stub rather than a Mockito mock — generatesADifferentClientIdAndSecretOnEachCall
  // below needs distinct values per call, same as the real SecureRandom-backed adapter would give.
  private final OAuthClientSecretGenerator secretGenerator = () -> UUID.randomUUID().toString();
  private AuditEventRecorder auditEvents;
  private RegisterOAuthClientService service;

  @BeforeEach
  void setUp() {
    oauthClients = mock(OAuthClientRepository.class);
    organizationExistsChecker = mock(OrganizationExistsChecker.class);
    environmentChecker = mock(OrganizationEnvironmentChecker.class);
    hasher = mock(ClientSecretHasher.class);
    auditEvents = mock(AuditEventRecorder.class);
    service =
        new RegisterOAuthClientService(
            oauthClients,
            organizationExistsChecker,
            environmentChecker,
            hasher,
            secretGenerator,
            auditEvents);

    when(organizationExistsChecker.exists(organizationId)).thenReturn(true);
    when(hasher.hash(anyString())).thenReturn("argon2id$hashed");
  }

  @Test
  void registersAndPersistsANewClientWithAServerGeneratedIdAndSecret() {
    RegisterOAuthClientResult result =
        service.handle(
            new RegisterOAuthClientCommand(
                organizationId,
                List.of("https://jobseeker.example.com/callback"),
                List.of("authorization_code"),
                List.of("openid"),
                true,
                List.of(),
                ACTOR));

    assertThat(result.client().organizationId()).isEqualTo(organizationId);
    assertThat(result.client().clientId()).isNotBlank();
    assertThat(result.client().clientSecretHash()).isEqualTo("argon2id$hashed");
    assertThat(result.rawClientSecret()).isNotBlank();
    verify(oauthClients).save(result.client());
  }

  // SDE-III review, 2026-09-11: RegisterOAuthClientService used to audit nothing at all — the one
  // create-shaped use case in this module that didn't. This is the real, load-bearing behavior
  // proving that gap is closed, not just that the command accepts an actor.
  @Test
  void auditsTheRegistrationUnderTheGivenActorNeverLoggingTheRawSecret() {
    RegisterOAuthClientResult result =
        service.handle(
            new RegisterOAuthClientCommand(
                organizationId,
                List.of("https://jobseeker.example.com/callback"),
                List.of("authorization_code"),
                List.of("openid"),
                true,
                List.of(),
                ACTOR));

    verify(auditEvents)
        .write(
            ACTOR,
            "oauth_client.registered",
            "Organization",
            organizationId.toString(),
            "clientId=" + result.client().clientId());
  }

  @Test
  void neverHashesOrPersistsTheRawSecretItself() {
    RegisterOAuthClientResult result =
        service.handle(
            new RegisterOAuthClientCommand(
                organizationId,
                List.of("https://jobseeker.example.com/callback"),
                List.of("authorization_code"),
                List.of("openid"),
                true,
                List.of(),
                ACTOR));

    // The stored hash must never equal the raw secret handed back to the caller — that would mean
    // the "hasher" silently did nothing.
    assertThat(result.client().clientSecretHash()).isNotEqualTo(result.rawClientSecret());
  }

  @Test
  void generatesADifferentClientIdAndSecretOnEachCall() {
    RegisterOAuthClientCommand command =
        new RegisterOAuthClientCommand(
            organizationId,
            List.of("https://jobseeker.example.com/callback"),
            List.of("authorization_code"),
            List.of("openid"),
            true,
            List.of(),
            ACTOR);

    RegisterOAuthClientResult first = service.handle(command);
    RegisterOAuthClientResult second = service.handle(command);

    assertThat(first.client().clientId()).isNotEqualTo(second.client().clientId());
    assertThat(first.rawClientSecret()).isNotEqualTo(second.rawClientSecret());
  }

  @Test
  void rejectsRegistrationUnderANonExistentOrganization() {
    // Command construction pulled out of the lambda passed to isThrownBy — same rationale as
    // RegisterAccountServiceTest's own equivalent test: with it inside, the lambda has two
    // invocations that could throw, leaving it ambiguous which one a future reader (or static
    // analysis) should credit for the exception.
    UUID unknownOrganizationId = UUID.randomUUID();
    when(organizationExistsChecker.exists(unknownOrganizationId)).thenReturn(false);
    RegisterOAuthClientCommand command =
        new RegisterOAuthClientCommand(
            unknownOrganizationId,
            List.of("https://jobseeker.example.com/callback"),
            List.of("authorization_code"),
            List.of("openid"),
            true,
            List.of(),
            ACTOR);

    assertThatExceptionOfType(OrganizationNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(oauthClients, never()).save(any());
  }

  // TD-FUT-018: the real, load-bearing behavior — the command's own value actually reaches the
  // persisted OAuthClient, not silently dropped between the two.
  @Test
  void passesThroughTheGivenPostLogoutRedirectUris() {
    RegisterOAuthClientResult result =
        service.handle(
            new RegisterOAuthClientCommand(
                organizationId,
                List.of("https://jobseeker.example.com/callback"),
                List.of("authorization_code"),
                List.of("openid"),
                true,
                List.of("https://jobseeker.example.com/logged-out"),
                ACTOR));

    assertThat(result.client().postLogoutRedirectUris())
        .containsExactly("https://jobseeker.example.com/logged-out");
  }

  // SDE-III feature build, 2026-09-04 (Clerk Development/Production instances analysis).
  @Test
  void prefixesTheClientIdWithLiveForAProductionOrganization() {
    when(environmentChecker.isDevelopment(organizationId)).thenReturn(false);

    RegisterOAuthClientResult result =
        service.handle(
            new RegisterOAuthClientCommand(
                organizationId,
                List.of("https://jobseeker.example.com/callback"),
                List.of("authorization_code"),
                List.of("openid"),
                true,
                List.of(),
                ACTOR));

    assertThat(result.client().clientId()).startsWith("live_");
  }

  @Test
  void prefixesTheClientIdWithTestForADevelopmentOrganization() {
    when(environmentChecker.isDevelopment(organizationId)).thenReturn(true);

    RegisterOAuthClientResult result =
        service.handle(
            new RegisterOAuthClientCommand(
                organizationId,
                List.of("https://jobseeker.example.com/callback"),
                List.of("authorization_code"),
                List.of("openid"),
                true,
                List.of(),
                ACTOR));

    assertThat(result.client().clientId()).startsWith("test_");
  }
}
