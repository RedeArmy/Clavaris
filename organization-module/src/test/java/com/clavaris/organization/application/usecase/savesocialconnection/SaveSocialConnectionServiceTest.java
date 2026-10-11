package com.clavaris.organization.application.usecase.savesocialconnection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.application.usecase.setorganizationsocialcredential.OrganizationSocialCredentialCipher;
import com.clavaris.organization.application.usecase.setorganizationsocialcredential.OrganizationSocialCredentialRepository;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.OrganizationSocialCredential;
import com.clavaris.organization.domain.model.SocialConnectionSettings;
import com.clavaris.organization.domain.model.SocialProvider;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SaveSocialConnectionServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformAccount(UUID.randomUUID());

  private OrganizationRepository organizations;
  private OrganizationSocialCredentialRepository credentials;
  private OrganizationSocialCredentialCipher cipher;
  private SocialConnectionSettingsRepository settings;
  private AuditEventRecorder auditEvents;
  private SaveSocialConnectionService service;

  @BeforeEach
  void setUp() {
    organizations = mock(OrganizationRepository.class);
    credentials = mock(OrganizationSocialCredentialRepository.class);
    cipher = mock(OrganizationSocialCredentialCipher.class);
    settings = mock(SocialConnectionSettingsRepository.class);
    auditEvents = mock(AuditEventRecorder.class);
    service =
        new SaveSocialConnectionService(organizations, credentials, cipher, settings, auditEvents);
    when(cipher.encrypt(anyString())).thenAnswer(call -> "enc(" + call.getArgument(0) + ")");
    when(credentials.findByOrganizationIdAndProvider(any(), any())).thenReturn(Optional.empty());
    when(settings.findByOrganizationIdAndProvider(any(), any())).thenReturn(Optional.empty());
  }

  private Organization development() {
    final Organization organization = Organization.register("Acme", UUID.randomUUID());
    when(organizations.findById(organization.id())).thenReturn(Optional.of(organization));
    return organization;
  }

  private Organization production(final String... enabledProviders) {
    Organization organization =
        Organization.registerProductionEnvironment("Acme", UUID.randomUUID(), UUID.randomUUID());
    if (enabledProviders.length > 0) {
      organization = organization.withSocialLoginPolicy(true, List.of(enabledProviders));
    }
    when(organizations.findById(organization.id())).thenReturn(Optional.of(organization));
    return organization;
  }

  private SaveSocialConnectionCommand command(
      final Organization organization,
      final SocialProvider provider,
      final boolean enabled,
      final String clientId,
      final String secret,
      final boolean block,
      final boolean selector) {
    return new SaveSocialConnectionCommand(
        organization.id(), provider, enabled, clientId, secret, block, selector, ACTOR);
  }

  @Test
  void aDevelopmentOrganizationEnablesAProviderOnTheSharedApp() {
    final Organization organization = development();

    service.handle(command(organization, SocialProvider.GOOGLE, true, "", "", true, false));

    final ArgumentCaptor<Organization> saved = ArgumentCaptor.forClass(Organization.class);
    verify(organizations).save(saved.capture());
    assertThat(saved.getValue().socialLoginEnabled()).isTrue();
    assertThat(saved.getValue().allowedSocialProviders()).containsExactly("GOOGLE");
    verify(credentials, never()).save(any());
    final ArgumentCaptor<SocialConnectionSettings> savedSettings =
        ArgumentCaptor.forClass(SocialConnectionSettings.class);
    verify(settings).save(savedSettings.capture());
    assertThat(savedSettings.getValue().blockEmailSubaddresses()).isTrue();
    assertThat(savedSettings.getValue().alwaysShowAccountSelector()).isFalse();
    verify(auditEvents)
        .write(eq(ACTOR), eq("social_login_policy.set"), eq("Organization"), any(), any());
    verify(auditEvents)
        .write(
            eq(ACTOR),
            eq("organization.social_connection_updated"),
            eq("Organization"),
            any(),
            any());
  }

  // ADR-0022: a DEVELOPMENT sandbox always uses the shared Clavaris app.
  @Test
  void aDevelopmentOrganizationCannotBringCredentials() {
    final Organization organization = development();
    final SaveSocialConnectionCommand command =
        command(organization, SocialProvider.GOOGLE, true, "client", "secret", false, false);

    assertThatThrownBy(() -> service.handle(command))
        .isInstanceOf(CustomCredentialsNotAllowedException.class);

    verify(organizations, never()).save(any());
    verifyNoInteractions(auditEvents);
  }

  // A PRODUCTION Organization signs in only through its own OAuth app.
  @Test
  void aProductionOrganizationCannotEnableAProviderWithNoCredentials() {
    final Organization organization = production();
    final SaveSocialConnectionCommand command =
        command(organization, SocialProvider.GITHUB, true, "", "", false, false);

    assertThatThrownBy(() -> service.handle(command))
        .isInstanceOf(CredentialsRequiredException.class);

    verify(organizations, never()).save(any());
  }

  @Test
  void aProductionOrganizationSavesItsCredentialsAndEnablesInOneStep() {
    final Organization organization = production();
    // The first save finds nothing; the check that follows it finds what was just saved.
    when(credentials.findByOrganizationIdAndProvider(organization.id(), SocialProvider.GOOGLE))
        .thenReturn(Optional.empty())
        .thenReturn(
            Optional.of(
                OrganizationSocialCredential.define(
                    organization.id(), SocialProvider.GOOGLE, "client-id", "enc(secret)")));

    service.handle(
        command(organization, SocialProvider.GOOGLE, true, " client-id ", "secret", true, true));

    final ArgumentCaptor<OrganizationSocialCredential> saved =
        ArgumentCaptor.forClass(OrganizationSocialCredential.class);
    verify(credentials).save(saved.capture());
    assertThat(saved.getValue().clientId()).isEqualTo("client-id");
    assertThat(saved.getValue().clientSecretEncrypted()).isEqualTo("enc(secret)");
    verify(organizations).save(any());
    // The secret is encrypted before it is stored and never reaches an audit event.
    verify(auditEvents, never()).write(any(), any(), any(), any(), contains("secret"));
    verify(auditEvents)
        .write(
            eq(ACTOR), eq("organization.social_credential_set"), eq("Organization"), any(), any());
  }

  @Test
  void aFirstCredentialNeedsBothTheClientIdAndTheSecret() {
    final Organization organization = production();
    final SaveSocialConnectionCommand command =
        command(organization, SocialProvider.GOOGLE, false, "client-id", "", false, false);

    assertThatThrownBy(() -> service.handle(command))
        .isInstanceOf(IncompleteCredentialsException.class);

    verify(credentials, never()).save(any());
  }

  // The secret is never shown again, so a blank one keeps the secret already saved.
  @Test
  void aBlankSecretKeepsTheOneAlreadySaved() {
    final Organization organization = production("GOOGLE");
    final OrganizationSocialCredential existing =
        OrganizationSocialCredential.define(
            organization.id(), SocialProvider.GOOGLE, "old-client", "enc(old-secret)");
    when(credentials.findByOrganizationIdAndProvider(organization.id(), SocialProvider.GOOGLE))
        .thenReturn(Optional.of(existing));

    service.handle(
        command(organization, SocialProvider.GOOGLE, true, "new-client", "", false, false));

    final ArgumentCaptor<OrganizationSocialCredential> saved =
        ArgumentCaptor.forClass(OrganizationSocialCredential.class);
    verify(credentials).save(saved.capture());
    assertThat(saved.getValue().clientId()).isEqualTo("new-client");
    assertThat(saved.getValue().clientSecretEncrypted()).isEqualTo("enc(old-secret)");
    verify(cipher, never()).encrypt(any());
  }

  @Test
  void aBlankClientIdKeepsTheOneAlreadySaved() {
    final Organization organization = production("GOOGLE");
    final OrganizationSocialCredential existing =
        OrganizationSocialCredential.define(
            organization.id(), SocialProvider.GOOGLE, "old-client", "enc(old-secret)");
    when(credentials.findByOrganizationIdAndProvider(organization.id(), SocialProvider.GOOGLE))
        .thenReturn(Optional.of(existing));

    service.handle(
        command(organization, SocialProvider.GOOGLE, true, "", "new-secret", false, false));

    final ArgumentCaptor<OrganizationSocialCredential> saved =
        ArgumentCaptor.forClass(OrganizationSocialCredential.class);
    verify(credentials).save(saved.capture());
    assertThat(saved.getValue().clientId()).isEqualTo("old-client");
    assertThat(saved.getValue().clientSecretEncrypted()).isEqualTo("enc(new-secret)");
  }

  @Test
  void disablingOneProviderLeavesTheOthersOn() {
    final Organization organization =
        development().withSocialLoginPolicy(true, List.of("GOOGLE", "GITHUB"));
    when(organizations.findById(organization.id())).thenReturn(Optional.of(organization));

    service.handle(command(organization, SocialProvider.GOOGLE, false, "", "", false, false));

    final ArgumentCaptor<Organization> saved = ArgumentCaptor.forClass(Organization.class);
    verify(organizations).save(saved.capture());
    assertThat(saved.getValue().socialLoginEnabled()).isTrue();
    assertThat(saved.getValue().allowedSocialProviders()).containsExactly("GITHUB");
  }

  @Test
  void disablingTheLastProviderTurnsSocialLoginOff() {
    final Organization organization = development().withSocialLoginPolicy(true, List.of("GOOGLE"));
    when(organizations.findById(organization.id())).thenReturn(Optional.of(organization));

    service.handle(command(organization, SocialProvider.GOOGLE, false, "", "", false, false));

    final ArgumentCaptor<Organization> saved = ArgumentCaptor.forClass(Organization.class);
    verify(organizations).save(saved.capture());
    assertThat(saved.getValue().socialLoginEnabled()).isFalse();
    assertThat(saved.getValue().allowedSocialProviders()).isEmpty();
  }

  // Nothing is written, or audited, when nothing changed.
  @Test
  void savingTheSameThingAgainWritesAndAuditsNothing() {
    final Organization organization = development().withSocialLoginPolicy(true, List.of("GOOGLE"));
    when(organizations.findById(organization.id())).thenReturn(Optional.of(organization));
    when(settings.findByOrganizationIdAndProvider(organization.id(), SocialProvider.GOOGLE))
        .thenReturn(
            Optional.of(
                SocialConnectionSettings.define(
                    organization.id(), SocialProvider.GOOGLE, true, false)));

    service.handle(command(organization, SocialProvider.GOOGLE, true, "", "", true, false));

    verify(organizations, never()).save(any());
    verify(settings, never()).save(any());
    verify(credentials, never()).save(any());
    verifyNoInteractions(auditEvents);
  }

  @Test
  void anOrganizationThatDoesNotExistIsNotFound() {
    final UUID missing = UUID.randomUUID();
    when(organizations.findById(missing)).thenReturn(Optional.empty());
    final SaveSocialConnectionCommand command =
        new SaveSocialConnectionCommand(
            missing, SocialProvider.GOOGLE, true, "", "", false, false, ACTOR);

    assertThatThrownBy(() -> service.handle(command))
        .isInstanceOf(OrganizationNotFoundException.class);
  }
}
