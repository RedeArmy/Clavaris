package com.clavaris.identity.application.usecase.requestemailsigninlink;

import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;

public record RequestEmailSignInLinkCommand(OrganizationId organizationId, Email email) {}
