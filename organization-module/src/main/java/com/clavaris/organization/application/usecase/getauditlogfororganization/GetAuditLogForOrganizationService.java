package com.clavaris.organization.application.usecase.getauditlogfororganization;

import com.clavaris.common.application.port.AuditEventReader;
import com.clavaris.common.domain.model.AuditEvent;
import com.clavaris.common.domain.model.KeysetCursor;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

/**
 * The audit log of one Organization (TD-SEC-007), newest first, a page at a time.
 *
 * <p>Reads by the tenant key {@code audit_events} carries since TD-ARCH-038 ({@code
 * organization_id}), so the query is one indexed range scan whatever the Organization owns. It used
 * to be assembled by listing every workspace, membership, OAuth client and webhook endpoint the
 * Organization had and matching {@code (target_type, target_id) IN (...)}: cost grew with the
 * number of memberships, events about the Organization's <em>accounts</em> (suspension,
 * impersonation, password reset) were left out for being too many ids to enumerate, and there was
 * no way past the first 100 rows. All three are gone.
 *
 * <p>A page is {@value #PAGE_SIZE} events; one more is fetched to learn whether there is a next
 * page without a second query.
 */
@SuppressWarnings("PMD.LongVariable")
public class GetAuditLogForOrganizationService implements GetAuditLogForOrganizationUseCase {

  /* package */ static final int PAGE_SIZE = 100;

  private final AuditEventReader auditEvents;

  public GetAuditLogForOrganizationService(final AuditEventReader auditEvents) {
    this.auditEvents = auditEvents;
  }

  @Override
  @Transactional(readOnly = true)
  public AuditLogPage handle(final GetAuditLogForOrganizationQuery query) {
    final List<AuditEvent> fetched =
        auditEvents.findPageForOrganization(
            query.organizationId(), query.olderThan(), PAGE_SIZE + 1);
    if (fetched.size() <= PAGE_SIZE) {
      return new AuditLogPage(fetched, null);
    }
    final List<AuditEvent> page = fetched.subList(0, PAGE_SIZE);
    final AuditEvent last = page.getLast();
    return new AuditLogPage(page, new KeysetCursor(last.occurredAt(), last.id()));
  }
}
