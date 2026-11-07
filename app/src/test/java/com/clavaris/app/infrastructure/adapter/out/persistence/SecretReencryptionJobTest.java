package com.clavaris.app.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.clavaris.common.infrastructure.adapter.out.crypto.EnvelopeCipher;
import com.clavaris.common.infrastructure.adapter.out.crypto.SecretReencryptionStore;
import com.clavaris.common.infrastructure.adapter.out.crypto.StoredSecretReencryptor.Target;
import com.clavaris.common.infrastructure.adapter.out.persistence.PostgresAdvisoryJobLock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * TD-SEC-089: the one job sweeps every store, each under a lock named for it, and every column a
 * store lists. What it does to the rows is {@code StoredSecretReencryptor}'s, tested per store
 * against Postgres next to each cipher.
 */
class SecretReencryptionJobTest {

  private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

  private record FakeStore(String store, List<Target> targets) implements SecretReencryptionStore {
    @Override
    public EnvelopeCipher envelope() {
      return new EnvelopeCipher(store, "1", KEY, "");
    }
  }

  private static Target target(final String store, final String table, final String column) {
    return new Target(store, table, column, table + "." + column + "/", List.of("id"));
  }

  @Test
  void everyStoreIsSweptUnderItsOwnLockAndEveryColumnItListsIsRead() {
    final PostgresAdvisoryJobLock lock = mock(PostgresAdvisoryJobLock.class);
    doAnswer(
            invocation -> {
              invocation.<Runnable>getArgument(2).run();
              return null;
            })
        .when(lock)
        .runIfLockAcquired(any(), any(Logger.class), any(Runnable.class));
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final SecretReencryptionJob job =
        new SecretReencryptionJob(
            List.of(
                new FakeStore("first", List.of(target("first", "first_table", "secret"))),
                new FakeStore(
                    "second",
                    List.of(
                        target("second", "second_table", "current_secret"),
                        target("second", "second_table", "previous_secret")))),
            jdbc,
            lock,
            new DefaultListableBeanFactory()
                .getBeanProvider(
                    com.clavaris.common.application.port.SecurityMetricsRecorder.class),
            500);

    job.sweep();

    final ArgumentCaptor<String> names = ArgumentCaptor.forClass(String.class);
    verify(lock, times(2))
        .runIfLockAcquired(names.capture(), any(Logger.class), any(Runnable.class));
    assertThat(names.getAllValues())
        .containsExactly("secret_reencryption:first", "secret_reencryption:second");
    verify(jdbc).query(contains("FROM first_table"), anyRowMapper(), any(), any(), eq(500));
    verify(jdbc, times(1))
        .query(contains("current_secret AS stored"), anyRowMapper(), any(), any(), anyInt());
    verify(jdbc, times(1))
        .query(contains("previous_secret AS stored"), anyRowMapper(), any(), any(), anyInt());
    verify(jdbc, times(3)).query(contains("starts_with"), anyRowMapper(), any(), any(), anyInt());
  }

  @SuppressWarnings("unchecked")
  private static RowMapper<Object> anyRowMapper() {
    return any(RowMapper.class);
  }
}
