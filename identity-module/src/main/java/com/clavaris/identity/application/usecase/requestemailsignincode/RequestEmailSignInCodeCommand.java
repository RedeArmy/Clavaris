package com.clavaris.identity.application.usecase.requestemailsignincode;

import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;

/** Domain-shaped input — the web adapter's form maps into this, not the other way round. */
public record RequestEmailSignInCodeCommand(OrganizationId organizationId, Email email) {}
