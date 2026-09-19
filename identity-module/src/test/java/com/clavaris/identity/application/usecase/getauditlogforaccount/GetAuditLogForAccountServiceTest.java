package com.clavaris.identity.application.usecase.getauditlogforaccount;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.AuditEventReader;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.common.domain.model.AuditEvent;
import com.clavaris.common.domain.model.AuditEventTargetRef;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GetAuditLogForAccountServiceTest {

  @Test
  void queriesForExactlyThisAccountsOwnTargetRef() {
    AuditEventReader auditEvents = mock(AuditEventReader.class);
    UUID accountId = UUID.randomUUID();
    AuditEvent expectedEvent =
        AuditEvent.of(
            AuditActor.platformAccount(UUID.randomUUID()),
            "account.suspended",
            "Account",
            accountId.toString(),
            null);
    when(auditEvents.findRecentForTargets(
            List.of(new AuditEventTargetRef("Account", accountId.toString())), 100))
        .thenReturn(List.of(expectedEvent));

    GetAuditLogForAccountService service = new GetAuditLogForAccountService(auditEvents);

    List<AuditEvent> result = service.handle(accountId);

    assertThat(result).containsExactly(expectedEvent);
    verify(auditEvents)
        .findRecentForTargets(
            List.of(new AuditEventTargetRef("Account", accountId.toString())), 100);
  }

  @Test
  void returnsAnEmptyListForAnAccountWithNoAuditEvents() {
    AuditEventReader auditEvents = mock(AuditEventReader.class);
    UUID accountId = UUID.randomUUID();
    when(auditEvents.findRecentForTargets(
            eq(List.of(new AuditEventTargetRef("Account", accountId.toString()))), eq(100)))
        .thenReturn(List.of());

    GetAuditLogForAccountService service = new GetAuditLogForAccountService(auditEvents);

    assertThat(service.handle(accountId)).isEmpty();
  }
}
