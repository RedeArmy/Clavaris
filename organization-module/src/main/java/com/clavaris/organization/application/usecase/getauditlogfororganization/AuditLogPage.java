package com.clavaris.organization.application.usecase.getauditlogfororganization;

import com.clavaris.common.domain.model.AuditEvent;
import com.clavaris.common.domain.model.KeysetCursor;
import java.util.List;

/**
 * One page of an Organization's audit log, newest first.
 *
 * @param events the events on this page
 * @param nextOlderThan the cursor that asks for the page after this one, or {@code null} when this
 *     is the last
 */
public record AuditLogPage(List<AuditEvent> events, KeysetCursor nextOlderThan) {

  public boolean hasOlder() {
    return nextOlderThan != null;
  }
}
