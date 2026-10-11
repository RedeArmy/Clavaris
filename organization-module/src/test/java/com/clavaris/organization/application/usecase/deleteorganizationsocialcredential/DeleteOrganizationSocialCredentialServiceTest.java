package com.clavaris.organization.application.usecase.deleteorganizationsocialcredential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.application.usecase.setorganizationsocialcredential.OrganizationSocialCredentialRepository;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.SocialProvider;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DeleteOrganizationSocialCredentialServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformClient("test-client");

  private OrganizationRepository organizations;
  private OrganizationSocialCredentialRepository credentials;
  private AuditEventRecorder auditEvents;
  private DeleteOrganizationSocialCredentialService service;

  @BeforeEach
  void setUp() {
    organizations = mock(OrganizationRepository.class);
    credentials = mock(OrganizationSocialCredentialRepository.class);
    auditEvents = mock(AuditEventRecorder.class);
    service =
        new DeleteOrganizationSocialCredentialService(organizations, credentials, auditEvents);
  }

  private DeleteOrganizationSocialCredentialCommand command(final Organization organization) {
    return new DeleteOrganizationSocialCredentialCommand(
        organization.id(), SocialProvider.GOOGLE, ACTOR);
  }

  @Test
  void removesTheCredentialAndAuditsIt() {
    final Organization development = Organization.register("Acme", UUID.randomUUID());
    when(organizations.findById(development.id())).thenReturn(Optional.of(development));

    service.handle(command(development));

    verify(credentials).deleteByOrganizationIdAndProvider(development.id(), SocialProvider.GOOGLE);
    verify(auditEvents)
        .write(
            eq(ACTOR),
            eq("organization.social_credential_deleted"),
            eq("Organization"),
            eq(development.id().toString()),
            any());
  }

  // Without its credentials a PRODUCTION provider would be offered and then fail, so it is
  // switched off with them.
  @Test
  void removingAProductionProvidersCredentialsSwitchesItOff() {
    final Organization production =
        Organization.registerProductionEnvironment("Acme", UUID.randomUUID(), UUID.randomUUID())
            .withSocialLoginPolicy(true, List.of("GOOGLE", "GITHUB"));
    when(organizations.findById(production.id())).thenReturn(Optional.of(production));

    service.handle(command(production));

    final ArgumentCaptor<Organization> saved = ArgumentCaptor.forClass(Organization.class);
    verify(organizations).save(saved.capture());
    assertThat(saved.getValue().allowedSocialProviders()).containsExactly("GITHUB");
    assertThat(saved.getValue().socialLoginEnabled()).isTrue();
    verify(auditEvents)
        .write(eq(ACTOR), eq("social_login_policy.set"), eq("Organization"), any(), any());
  }

  @Test
  void removingTheLastProductionProvidersCredentialsTurnsSocialLoginOff() {
    final Organization production =
        Organization.registerProductionEnvironment("Acme", UUID.randomUUID(), UUID.randomUUID())
            .withSocialLoginPolicy(true, List.of("GOOGLE"));
    when(organizations.findById(production.id())).thenReturn(Optional.of(production));

    service.handle(command(production));

    final ArgumentCaptor<Organization> saved = ArgumentCaptor.forClass(Organization.class);
    verify(organizations).save(saved.capture());
    assertThat(saved.getValue().socialLoginEnabled()).isFalse();
  }

  // A DEVELOPMENT Organization never had credentials in use, so its policy is left alone.
  @Test
  void aDevelopmentOrganizationKeepsItsPolicy() {
    final Organization development =
        Organization.register("Acme", UUID.randomUUID())
            .withSocialLoginPolicy(true, List.of("GOOGLE"));
    when(organizations.findById(development.id())).thenReturn(Optional.of(development));

    service.handle(command(development));

    verify(organizations, never()).save(any());
  }

  @Test
  void aProductionProviderThatWasNotEnabledIsLeftAlone() {
    final Organization production =
        Organization.registerProductionEnvironment("Acme", UUID.randomUUID(), UUID.randomUUID());
    when(organizations.findById(production.id())).thenReturn(Optional.of(production));

    service.handle(command(production));

    verify(organizations, never()).save(any());
  }
}
