package com.clavaris.organization.application.usecase.savesocialconnection;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.application.usecase.setorganizationsocialcredential.OrganizationSocialCredentialCipher;
import com.clavaris.organization.application.usecase.setorganizationsocialcredential.OrganizationSocialCredentialRepository;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.OrganizationEnvironment;
import com.clavaris.organization.domain.model.OrganizationSocialCredential;
import com.clavaris.organization.domain.model.SocialConnectionSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration for {@link SaveSocialConnectionUseCase}: the dashboard's "Connections" page, which
 * saves a provider's switch, its credentials and its settings together, so a failure part way
 * leaves none of them changed.
 *
 * <p>The rules, from ADR-0020 and ADR-0022 as amended for this page: a DEVELOPMENT Organization
 * always signs in through the shared Clavaris app, so it may not bring credentials; a PRODUCTION
 * Organization signs in only through its own, so it cannot enable a provider until it has them. A
 * blank client id or secret keeps the one already saved, so a connection can be edited without
 * retyping a secret that is never shown again.
 *
 * <p>Only what actually changed is written, and each change is audited on its own, never with the
 * secret.
 */
public class SaveSocialConnectionService implements SaveSocialConnectionUseCase {

  private static final String ORGANIZATION_ENTITY = "Organization";
  private static final String PROVIDER_DETAIL = "provider=";

  private final OrganizationRepository organizations;
  private final OrganizationSocialCredentialRepository credentials;
  private final OrganizationSocialCredentialCipher cipher;
  private final SocialConnectionSettingsRepository settings;
  private final AuditEventRecorder auditEvents;

  public SaveSocialConnectionService(
      final OrganizationRepository organizations,
      final OrganizationSocialCredentialRepository credentials,
      final OrganizationSocialCredentialCipher cipher,
      final SocialConnectionSettingsRepository settings,
      final AuditEventRecorder auditEvents) {
    this.organizations = organizations;
    this.credentials = credentials;
    this.cipher = cipher;
    this.settings = settings;
    this.auditEvents = auditEvents;
  }

  @Override
  @Transactional
  public void handle(final SaveSocialConnectionCommand command) {
    final Organization organization =
        organizations
            .findById(command.organizationId())
            .orElseThrow(() -> new OrganizationNotFoundException(command.organizationId()));
    final boolean production = organization.environment() == OrganizationEnvironment.PRODUCTION;

    final boolean credentialsChanged = saveCredentials(command, production);
    if (command.enabled()
        && production
        && credentials
            .findByOrganizationIdAndProvider(command.organizationId(), command.provider())
            .isEmpty()) {
      throw new CredentialsRequiredException(command.organizationId(), command.provider());
    }
    final boolean policyChanged = savePolicy(command, organization);
    final boolean settingsChanged = saveSettings(command);

    if (credentialsChanged) {
      audit(command, "organization.social_credential_set", PROVIDER_DETAIL + command.provider());
    }
    if (policyChanged) {
      audit(
          command,
          "social_login_policy.set",
          PROVIDER_DETAIL + command.provider() + " enabled=" + command.enabled());
    }
    if (settingsChanged) {
      audit(
          command,
          "organization.social_connection_updated",
          PROVIDER_DETAIL
              + command.provider()
              + " blockEmailSubaddresses="
              + command.blockEmailSubaddresses()
              + " alwaysShowAccountSelector="
              + command.alwaysShowAccountSelector());
    }
  }

  // Returns whether anything was written. A blank half keeps the saved one; a first-time credential
  // needs both.
  private boolean saveCredentials(final SaveSocialConnectionCommand command, final boolean prod) {
    final boolean given = hasText(command.clientId()) || hasText(command.rawClientSecret());
    if (given) {
      if (!prod) {
        throw new CustomCredentialsNotAllowedException(command.organizationId());
      }
      credentials.save(mergedCredential(command));
    }
    return given;
  }

  private OrganizationSocialCredential mergedCredential(final SaveSocialConnectionCommand command) {
    final boolean givenId = hasText(command.clientId());
    final boolean givenSecret = hasText(command.rawClientSecret());
    final Optional<OrganizationSocialCredential> existing =
        credentials.findByOrganizationIdAndProvider(command.organizationId(), command.provider());
    if (existing.isEmpty() && !(givenId && givenSecret)) {
      throw new IncompleteCredentialsException();
    }
    final String clientId =
        givenId
            ? command.clientId().strip()
            : existing.map(OrganizationSocialCredential::clientId).orElseThrow();
    final String secret =
        givenSecret
            ? cipher.encrypt(command.rawClientSecret())
            : existing.map(OrganizationSocialCredential::clientSecretEncrypted).orElseThrow();
    return existing
        .map(current -> current.withCredential(clientId, secret))
        .orElseGet(
            () ->
                OrganizationSocialCredential.define(
                    command.organizationId(), command.provider(), clientId, secret));
  }

  // Adds or removes the provider from the Organization's allowed list; social login as a whole is
  // on exactly while that list is not empty. Returns whether the provider's state changed.
  private boolean savePolicy(
      final SaveSocialConnectionCommand command, final Organization organization) {
    final String name = command.provider().name();
    final List<String> providers = new ArrayList<>(organization.allowedSocialProviders());
    final boolean wasEnabled = organization.socialLoginEnabled() && providers.contains(name);
    if (command.enabled() && !providers.contains(name)) {
      providers.add(name);
    } else if (!command.enabled()) {
      providers.remove(name);
    }
    final boolean enabled = !providers.isEmpty();
    if (enabled != organization.socialLoginEnabled()
        || !providers.equals(organization.allowedSocialProviders())) {
      organizations.save(organization.withSocialLoginPolicy(enabled, providers));
    }
    return wasEnabled != command.enabled();
  }

  // Returns whether the settings differ from what the connection had (or the unset defaults).
  private boolean saveSettings(final SaveSocialConnectionCommand command) {
    final Optional<SocialConnectionSettings> existing =
        settings.findByOrganizationIdAndProvider(command.organizationId(), command.provider());
    final SocialConnectionSettings current =
        existing.orElseGet(
            () -> SocialConnectionSettings.unset(command.organizationId(), command.provider()));
    final boolean changed =
        current.blockEmailSubaddresses() != command.blockEmailSubaddresses()
            || current.alwaysShowAccountSelector() != command.alwaysShowAccountSelector();
    if (changed) {
      settings.save(
          current.with(command.blockEmailSubaddresses(), command.alwaysShowAccountSelector()));
    }
    return changed;
  }

  private void audit(
      final SaveSocialConnectionCommand command, final String action, final String detail) {
    auditEvents.write(
        command.actor(), action, ORGANIZATION_ENTITY, command.organizationId().toString(), detail);
  }

  private static boolean hasText(final String value) {
    return value != null && !value.isBlank();
  }
}
