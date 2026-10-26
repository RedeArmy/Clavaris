package com.clavaris.organization.application.usecase.getauditlogfororganization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.AuditEventReader;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.common.domain.model.AuditEvent;
import com.clavaris.common.domain.model.KeysetCursor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** TD-ARCH-038: the Organization's log is one tenant-keyed, paged read. */
class GetAuditLogForOrganizationServiceTest {

  private static final int PAGE = GetAuditLogForOrganizationService.PAGE_SIZE;

  private final AuditEventReader reader = mock(AuditEventReader.class);
  private final GetAuditLogForOrganizationService service =
      new GetAuditLogForOrganizationService(reader);
  private final UUID organizationId = UUID.randomUUID();

  private static AuditEvent event(final int minutesAgo) {
    return AuditEvent.reconstitute(
        UUID.randomUUID(),
        AuditActor.platformAccount(UUID.randomUUID()),
        "organization.updated",
        "Organization",
        UUID.randomUUID().toString(),
        null,
        Instant.parse("2026-10-25T12:00:00Z").minusSeconds(60L * minutesAgo));
  }

  private static List<AuditEvent> events(final int count) {
    final List<AuditEvent> all = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      all.add(event(i));
    }
    return all;
  }

  @Test
  void aShortLogIsOnePageWithNothingOlder() {
    final List<AuditEvent> fetched = events(3);
    when(reader.findPageForOrganization(organizationId, null, PAGE + 1)).thenReturn(fetched);

    final AuditLogPage page =
        service.handle(GetAuditLogForOrganizationQuery.newest(organizationId));

    assertThat(page.events()).isEqualTo(fetched);
    assertThat(page.hasOlder()).isFalse();
  }

  @Test
  void aFullPageAsksForOneMoreRowToKnowWhetherThereIsAnOlderPage() {
    final List<AuditEvent> fetched = events(PAGE + 1);
    when(reader.findPageForOrganization(organizationId, null, PAGE + 1)).thenReturn(fetched);

    final AuditLogPage page =
        service.handle(GetAuditLogForOrganizationQuery.newest(organizationId));

    assertThat(page.events()).hasSize(PAGE).doesNotContain(fetched.getLast());
    assertThat(page.hasOlder()).isTrue();
    final AuditEvent lastShown = page.events().getLast();
    assertThat(page.nextOlderThan())
        .isEqualTo(new KeysetCursor(lastShown.occurredAt(), lastShown.id()));
  }

  @Test
  void exactlyOnePageOfRowsHasNoOlderPage() {
    when(reader.findPageForOrganization(organizationId, null, PAGE + 1)).thenReturn(events(PAGE));

    assertThat(service.handle(GetAuditLogForOrganizationQuery.newest(organizationId)).hasOlder())
        .isFalse();
  }

  @Test
  void passesTheCursorOfThePreviousPageToTheReader() {
    final KeysetCursor cursor =
        new KeysetCursor(Instant.parse("2026-10-25T10:00:00Z"), UUID.randomUUID());
    when(reader.findPageForOrganization(organizationId, cursor, PAGE + 1)).thenReturn(events(2));

    service.handle(new GetAuditLogForOrganizationQuery(organizationId, cursor));

    verify(reader).findPageForOrganization(organizationId, cursor, PAGE + 1);
  }
}
