package com.clavaris.identity.application.usecase.getauditlogforaccount;

import com.clavaris.common.application.port.AuditEventReader;
import com.clavaris.common.domain.model.AuditEvent;
import com.clavaris.common.domain.model.AuditEventTargetRef;
import java.util.List;
import java.util.UUID;

/** Orchestration for {@link GetAuditLogForAccountUseCase}. */
public class GetAuditLogForAccountService implements GetAuditLogForAccountUseCase {

  // Same "cheap to hold in memory, no real pagination yet" posture
  // GetAuditLogForOrganizationService's own MAX_RESULTS already establishes.
  private static final int MAX_RESULTS = 100;

  private final AuditEventReader auditEvents;

  public GetAuditLogForAccountService(final AuditEventReader auditEvents) {
    this.auditEvents = auditEvents;
  }

  @Override
  public List<AuditEvent> handle(final UUID accountId) {
    return auditEvents.findRecentForTargets(
        List.of(new AuditEventTargetRef("Account", accountId.toString())), MAX_RESULTS);
  }
}
