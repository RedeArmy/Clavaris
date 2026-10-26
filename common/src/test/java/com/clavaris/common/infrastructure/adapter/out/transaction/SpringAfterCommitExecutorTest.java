package com.clavaris.common.infrastructure.adapter.out.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class SpringAfterCommitExecutorTest {

  private final SpringAfterCommitExecutor executor = new SpringAfterCommitExecutor();

  @AfterEach
  void clearSynchronization() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void runsImmediatelyWhenThereIsNoTransaction() {
    boolean[] ran = {false};

    executor.run(() -> ran[0] = true);

    assertThat(ran[0]).isTrue();
  }

  @Test
  void waitsForTheCommitInsideATransaction() {
    TransactionSynchronizationManager.initSynchronization();
    boolean[] ran = {false};

    executor.run(() -> ran[0] = true);

    assertThat(ran[0]).as("not before the commit").isFalse();
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(TransactionSynchronization::afterCommit);
    assertThat(ran[0]).isTrue();
  }

  @Test
  void neverRunsOnARollback() {
    TransactionSynchronizationManager.initSynchronization();
    boolean[] ran = {false};
    executor.run(() -> ran[0] = true);

    List<TransactionSynchronization> synchronizations =
        TransactionSynchronizationManager.getSynchronizations();
    synchronizations.forEach(
        synchronization ->
            synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

    assertThat(ran[0]).isFalse();
  }

  @Test
  void aFailureAfterTheCommitIsLoggedNotThrown() {
    TransactionSynchronizationManager.initSynchronization();
    executor.run(
        () -> {
          throw new IllegalStateException("redis is down");
        });

    assertThatCode(
            () ->
                TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit))
        .doesNotThrowAnyException();
  }
}
