package com.clavaris.organization.application.usecase.deleteorganizationsocialcredential;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.domain.model.SocialProvider;
import java.util.UUID;

public record DeleteOrganizationSocialCredentialCommand(
    UUID organizationId, SocialProvider provider, AuditActor actor) {}
