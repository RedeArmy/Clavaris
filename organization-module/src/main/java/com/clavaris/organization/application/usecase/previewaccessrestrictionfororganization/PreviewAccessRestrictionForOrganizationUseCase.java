package com.clavaris.organization.application.usecase.previewaccessrestrictionfororganization;

import com.clavaris.organization.domain.service.AccessRestrictionDecision;

/**
 * TD-FUT-069: a dry run of the Organization's blocklist and allowlist. Reads the lists, writes
 * nothing and records nothing, so an operator can ask "would this address be let in, and by which
 * rule?" before turning an allowlist on or answering a support request.
 */
@FunctionalInterface
public interface PreviewAccessRestrictionForOrganizationUseCase {

  AccessRestrictionDecision handle(PreviewAccessRestrictionForOrganizationQuery query);
}
