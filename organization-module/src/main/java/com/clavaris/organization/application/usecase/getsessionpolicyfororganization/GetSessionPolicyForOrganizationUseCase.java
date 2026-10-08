package com.clavaris.organization.application.usecase.getsessionpolicyfororganization;

import com.clavaris.organization.domain.model.SessionPolicy;
import java.util.UUID;

/** Never empty — returns {@link SessionPolicy#defaults(UUID)} when no row exists. */
@FunctionalInterface
public interface GetSessionPolicyForOrganizationUseCase {

  SessionPolicy handle(UUID organizationId);
}
