package com.clavaris.common.application.port;

/**
 * Runs work once the surrounding transaction has committed (immediately when there is none).
 * TD-PERF-043: a use case that must do slow or non-transactional work as part of a database change
 * (revoking sessions in Redis, rewriting a key file) used to do it inside the transaction, holding
 * a pooled connection for as long as it took, and, for work that cannot be rolled back, destroying
 * state a later rollback would have wanted. Handing that work to this port keeps the transaction to
 * the database and means the work only happens for a change that was actually committed.
 *
 * <p>The action must not depend on the transaction (it runs outside it) and must be safe to lose if
 * the process dies after the commit: an implementation never lets the action's failure undo or fail
 * the committed change; it logs it.
 */
@FunctionalInterface
public interface AfterCommitExecutor {

  void run(Runnable action);
}
