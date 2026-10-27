package com.clavaris.common.infrastructure.adapter.out.transaction;

import com.clavaris.common.application.port.AfterCommitExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Registers the action as a Spring transaction synchronization, so it runs after the commit and not
 * at all on a rollback; with no transaction in progress it runs straight away. A failure is logged,
 * never thrown: by then the database change is committed and must not look failed to the caller
 * (TD-PERF-043).
 */
// PMD.AtLeastOneConstructor: no state, only the behaviour — same precedent as the stateless
// handlers in app.
@SuppressWarnings("PMD.AtLeastOneConstructor")
@Component
class SpringAfterCommitExecutor implements AfterCommitExecutor {

  private static final Logger LOG = LoggerFactory.getLogger(SpringAfterCommitExecutor.class);

  @Override
  public void run(final Runnable action) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              runLoggingFailure(action);
            }
          });
    } else {
      runLoggingFailure(action);
    }
  }

  // PMD.AvoidCatchingGenericException: the action is arbitrary caller work and, by this port's
  // contract, whatever it throws must not turn a committed change into a failed request.
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private static void runLoggingFailure(final Runnable action) {
    try {
      action.run();
    } catch (final RuntimeException e) {
      LOG.error("event=after_commit_action_failed exceptionType={}", e.getClass().getName());
    }
  }
}
