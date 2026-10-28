package com.clavaris.identity.application.usecase.requestemailchange;

import com.clavaris.identity.domain.model.OrganizationId;

/**
 * Outbound port (TD-FUT-052): whether the Organization lets its End-Users change their own email. A
 * consuming application that keys its records on the email may want to be the only place it can
 * change. Implemented in {@code app} against the Organization's authentication policy;
 * identity-module does not read that table.
 */
@FunctionalInterface
public interface SelfServiceEmailChangePolicy {

  boolean isAllowed(OrganizationId organizationId);
}
