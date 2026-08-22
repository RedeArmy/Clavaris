package com.clavaris.identity.application.usecase.requestpasswordreset;

import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;

/** Domain-shaped input — the web adapter's form maps into this, not the other way round. */
public record RequestPasswordResetCommand(OrganizationId organizationId, Email email) {}
