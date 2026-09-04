package com.clavaris.organization.application.usecase.setorganizationsocialcredential;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.OrganizationEnvironment;
import com.clavaris.organization.domain.model.OrganizationSocialCredential;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration for {@link SetOrganizationSocialCredentialUseCase} — ADR-0022's own core write
 * path. Two guards, both enforced here (not in the domain model, which has no cross-aggregate
 * visibility into {@code Organization} itself — same division of responsibility {@code
 * CreateProductionEnvironmentService}'s own Javadoc already draws): the target Organization must be
 * {@code PRODUCTION} ({@link OrganizationNotProductionException}), and social login must already be
 * enabled for this exact provider via ADR-0020 Decision 3's own policy ({@link
 * SocialLoginNotEnabledForProviderException}) — bringing your own credentials is additive to that
 * gate, never a way around it.
 */
public class SetOrganizationSocialCredentialService
    implements SetOrganizationSocialCredentialUseCase {

  private final OrganizationRepository organizations;
  private final OrganizationSocialCredentialRepository credentials;
  private final OrganizationSocialCredentialCipher cipher;
  private final AuditEventRecorder auditEvents;

  public SetOrganizationSocialCredentialService(
      final OrganizationRepository organizations,
      final OrganizationSocialCredentialRepository credentials,
      final OrganizationSocialCredentialCipher cipher,
      final AuditEventRecorder auditEvents) {
    this.organizations = organizations;
    this.credentials = credentials;
    this.cipher = cipher;
    this.auditEvents = auditEvents;
  }

  @Override
  @Transactional
  public SetOrganizationSocialCredentialResult handle(
      final SetOrganizationSocialCredentialCommand command) {
    final Organization organization =
        organizations
            .findById(command.organizationId())
            .orElseThrow(() -> new OrganizationNotFoundException(command.organizationId()));

    if (organization.environment() != OrganizationEnvironment.PRODUCTION) {
      throw new OrganizationNotProductionException(command.organizationId());
    }
    if (!organization.socialLoginEnabled()
        || !organization.allowedSocialProviders().contains(command.provider().name())) {
      throw new SocialLoginNotEnabledForProviderException(
          command.organizationId(), command.provider());
    }

    final String encryptedSecret = cipher.encrypt(command.rawClientSecret());
    final OrganizationSocialCredential credential =
        credentials
            .findByOrganizationIdAndProvider(command.organizationId(), command.provider())
            .map(existing -> existing.withCredential(command.clientId(), encryptedSecret))
            .orElseGet(
                () ->
                    OrganizationSocialCredential.define(
                        command.organizationId(),
                        command.provider(),
                        command.clientId(),
                        encryptedSecret));

    credentials.save(credential);

    // Never logs the secret itself (BR-DATA-01) — only which provider changed, same shape
    // RotateWebhookEndpointSecretService's own audit write already establishes for an analogous
    // "a real secret was just replaced" event.
    auditEvents.write(
        command.actor(),
        "organization.social_credential_set",
        "Organization",
        command.organizationId().toString(),
        "provider=" + command.provider());

    return new SetOrganizationSocialCredentialResult(credential);
  }
}
