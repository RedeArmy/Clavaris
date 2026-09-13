package com.clavaris.organization.application.usecase.getauditlogfororganization;

import com.clavaris.common.domain.model.AuditEvent;
import java.util.List;
import java.util.UUID;

@FunctionalInterface
public interface GetAuditLogForOrganizationUseCase {

  List<AuditEvent> handle(UUID organizationId);
}
