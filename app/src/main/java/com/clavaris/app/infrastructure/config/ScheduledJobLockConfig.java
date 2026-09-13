package com.clavaris.app.infrastructure.config;

import com.clavaris.common.infrastructure.adapter.out.persistence.PostgresAdvisoryJobLock;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * TD-FUT-033: the one bean-wiring point for {@link PostgresAdvisoryJobLock} — {@code common} module
 * classes aren't component-scanned (this app's own {@code @SpringBootApplication} only scans {@code
 * com.clavaris.app} and below, same reasoning {@code EventOutboxRetentionSweeper}'s own Javadoc
 * already documents for why it's a plain static helper rather than a shared {@code @Component}).
 * Registering the one instance here makes it constructor-injectable into any {@code @Component}
 * across every module (organization-/identity-/webhook-module's own retention/ cleanup jobs
 * included) — Spring resolves beans by type across the whole {@code ApplicationContext}, not by
 * package, so the consumer's own package location doesn't matter once this bean exists in the
 * context.
 */
// PMD.AtLeastOneConstructor: this class holds no state of its own — a plain @Bean factory, same
// "intentionally empty" precedent GlobalExceptionHandler's own identical suppression establishes.
@SuppressWarnings("PMD.AtLeastOneConstructor")
@Configuration
class ScheduledJobLockConfig {

  @Bean
  /* package */ PostgresAdvisoryJobLock postgresAdvisoryJobLock(final DataSource dataSource) {
    return new PostgresAdvisoryJobLock(dataSource);
  }
}
