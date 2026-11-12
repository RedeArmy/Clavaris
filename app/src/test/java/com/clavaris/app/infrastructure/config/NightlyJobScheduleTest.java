package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * TD-PERF-061: a job that opens a transaction holds a pooled connection for it and a dedicated one
 * for its advisory lock, so two jobs started at the same minute can take four connections of a pool
 * of five to ten while the application serves traffic. Every cron-scheduled method in the
 * application therefore needs a time of its own; the schedule is in deployment-runbook.md.
 */
class NightlyJobScheduleTest {

  @Test
  void noTwoCronScheduledMethodsShareATime() throws ClassNotFoundException {
    final ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));

    final Map<String, List<String>> byCron = new HashMap<>();
    for (final BeanDefinition candidate : scanner.findCandidateComponents("com.clavaris")) {
      final Class<?> type = Class.forName(candidate.getBeanClassName());
      for (final Method method : type.getDeclaredMethods()) {
        for (final Scheduled scheduled : method.getAnnotationsByType(Scheduled.class)) {
          if (!scheduled.cron().isEmpty()) {
            byCron
                .computeIfAbsent(scheduled.cron(), k -> new ArrayList<>())
                .add(type.getSimpleName() + "." + method.getName());
          }
        }
      }
    }

    assertThat(byCron).as("the scan must find the cron-scheduled jobs").hasSizeGreaterThan(8);
    byCron.forEach(
        (cron, methods) -> assertThat(methods).as("jobs sharing the cron \"%s\"", cron).hasSize(1));
  }
}
