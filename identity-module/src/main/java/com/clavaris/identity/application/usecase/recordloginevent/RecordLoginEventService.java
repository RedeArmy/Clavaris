package com.clavaris.identity.application.usecase.recordloginevent;

import com.clavaris.identity.domain.model.LoginEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestration for {@link RecordLoginEventUseCase}.
 *
 * <p><b>Never lets a side-channel write fail an otherwise-successful login</b> — same guarantee
 * {@code RecordAccountLoginDeviceService}'s own Javadoc establishes for its own, adjacent concern
 * (and the same reason neither caller wraps this use case's own {@code handle} call in a try/catch,
 * trusting this guarantee). A failed insert here only costs one missing day on the activity
 * heatmap, never a lost sign-in.
 */
public class RecordLoginEventService implements RecordLoginEventUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(RecordLoginEventService.class);

  private final LoginEventRepository loginEvents;

  public RecordLoginEventService(final LoginEventRepository loginEvents) {
    this.loginEvents = loginEvents;
  }

  @SuppressWarnings("PMD.AvoidCatchingGenericException") // insert() can throw a Spring
  // DataAccessException like any other DB write; must never propagate (see class Javadoc).
  @Override
  public void handle(final RecordLoginEventCommand command) {
    try {
      loginEvents.insert(LoginEvent.occurNow(command.accountId(), command.organizationId()));
    } catch (final RuntimeException e) {
      LOG.warn("event=login_event_record_failed", e);
    }
  }
}
