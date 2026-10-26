package com.clavaris.organization.application.usecase.getauditlogfororganization;

@FunctionalInterface
public interface GetAuditLogForOrganizationUseCase {

  AuditLogPage handle(GetAuditLogForOrganizationQuery query);
}
