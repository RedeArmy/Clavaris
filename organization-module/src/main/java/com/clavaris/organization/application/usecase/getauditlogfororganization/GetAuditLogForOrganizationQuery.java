package com.clavaris.organization.application.usecase.getauditlogfororganization;

import com.clavaris.common.domain.model.KeysetCursor;
import java.util.UUID;

/**
 * @param organizationId whose audit log to read
 * @param olderThan the cursor of the last row of the page before, or {@code null} for the newest
 *     page
 */
public record GetAuditLogForOrganizationQuery(UUID organizationId, KeysetCursor olderThan) {

  public static GetAuditLogForOrganizationQuery newest(final UUID organizationId) {
    return new GetAuditLogForOrganizationQuery(organizationId, null);
  }
}
