package com.clavaris.organization.application.usecase.setorganizationsocialcredential;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.domain.model.SocialProvider;
import java.util.UUID;

/**
 * ADR-0022: operator-managed only in v1, same shape as {@code
 * SetRateLimitPolicyForOrganizationCommand} — this command is only ever reachable via the
 * platform-tier management API, never a tenant's own token.
 *
 * @param rawClientSecret the cleartext secret as supplied by the operator — encrypted by the
 *     service before ever reaching {@link OrganizationSocialCredentialRepository#save}, never
 *     itself persisted or logged.
 */
public record SetOrganizationSocialCredentialCommand(
    UUID organizationId,
    SocialProvider provider,
    String clientId,
    String rawClientSecret,
    AuditActor actor) {}
