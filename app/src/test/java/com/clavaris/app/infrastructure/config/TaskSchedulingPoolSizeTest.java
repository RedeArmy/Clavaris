package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * TD-PERF-003: proves the actual configuration, not just that some property spelled correctly would
 * work. Deliberately a plain {@link ApplicationContextRunner} scoped to only {@link
 * TaskSchedulingAutoConfiguration} — this concern has no dependency on a datasource, Redis, or any
 * other bean this app's full context needs, so proving it doesn't require Testcontainers or the
 * full {@code @SpringBootTest} startup cost every other test in this module pays.
 */
class TaskSchedulingPoolSizeTest {

  @Configuration
  @EnableScheduling
  static class SchedulingEnabledConfig {}

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
          .withUserConfiguration(SchedulingEnabledConfig.class);

  @Test
  void springBootsOwnDefaultIsStillOneWithoutAnyOverride() {
    // Confirmed live by decompiling spring-boot-autoconfigure-4.1.1.jar's own
    // TaskSchedulingProperties$Pool constructor bytecode (iconst_1) — this test re-asserts that
    // fact through the real autoconfiguration path (not just trusting the decompile forever), so
    // a future Spring Boot upgrade that silently changes this default fails a test here instead
    // of being rediscovered live in production.
    contextRunner.run(
        context -> {
          // getPoolSize() itself is a live thread count (ScheduledThreadPoolExecutor's own,
          // confirmed by decompiling this exact Spring Framework version's bytecode) — 0 until a
          // task actually runs, not the configured value. getCorePoolSize() is the configured
          // number this row is actually about.
          final ThreadPoolTaskScheduler scheduler = context.getBean(ThreadPoolTaskScheduler.class);
          assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(1);
        });
  }

  @Test
  void thisAppsOwnApplicationYmlOverridesToTheConfiguredPoolSize() {
    // ConfigDataApplicationContextInitializer processes the real app/src/main/resources/
    // application.yml from the classpath — the exact same file ClavarisApplication itself boots
    // with — not a hardcoded property value, so a typo'd key/nesting in that file (spring.tasks
    // instead of spring.task, e.g.) would fail this test instead of silently doing nothing.
    contextRunner
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .run(
            context -> {
              final ThreadPoolTaskScheduler scheduler =
                  context.getBean(ThreadPoolTaskScheduler.class);
              assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(5);
            });
  }
}
