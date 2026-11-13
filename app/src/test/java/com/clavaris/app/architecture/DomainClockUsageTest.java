package com.clavaris.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * TD-ARCH-052: a domain class that reads the wall clock itself cannot be tested at a boundary (a
 * token that expires at the exact instant, a lock that ends) without waiting for time to pass, and
 * its tests end up sleeping. Time is passed in by the use case, which reads one clock.
 *
 * <p>A ratchet, not a ban: {@code architecture/domain-clock-readers.txt} lists the domain classes
 * that still call {@code Instant.now()} and friends. A class not on the list that starts doing it
 * fails the build; a class on the list that no longer does fails it too, so the list can only
 * shrink. Fixing a class means deleting its line.
 */
class DomainClockUsageTest {

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("com.clavaris");

  private static final Set<String> CLOCK_READS =
      Set.of(
          "java.time.Instant.now",
          "java.time.OffsetDateTime.now",
          "java.time.LocalDateTime.now",
          "java.time.LocalDate.now",
          "java.time.ZonedDateTime.now",
          "java.time.Clock.systemUTC",
          "java.time.Clock.systemDefaultZone",
          "java.lang.System.currentTimeMillis");

  @Test
  void onlyTheListedDomainClassesReadTheWallClock() throws IOException {
    final Set<String> actual = new TreeSet<>();
    for (final JavaClass type : CLASSES) {
      if (type.getPackageName().contains(".domain") && readsTheClock(type)) {
        actual.add(type.getName());
      }
    }
    final Set<String> listed = listed();

    final Set<String> newOffenders = new TreeSet<>(actual);
    newOffenders.removeAll(listed);
    final Set<String> fixedButStillListed = new TreeSet<>(listed);
    fixedButStillListed.removeAll(actual);

    assertThat(newOffenders)
        .as(
            "domain classes that read the clock and are not on the list: pass the time in from the"
                + " use case instead (TD-ARCH-052)")
        .isEmpty();
    assertThat(fixedButStillListed)
        .as(
            "classes on architecture/domain-clock-readers.txt that no longer read the clock:"
                + " delete their line")
        .isEmpty();
  }

  private static boolean readsTheClock(final JavaClass type) {
    return type.getMethodCallsFromSelf().stream()
        .anyMatch(
            call -> CLOCK_READS.contains(call.getTargetOwner().getName() + "." + call.getName()));
  }

  private static Set<String> listed() throws IOException {
    try (InputStream in =
        DomainClockUsageTest.class.getResourceAsStream("/architecture/domain-clock-readers.txt")) {
      assertThat(in).as("architecture/domain-clock-readers.txt").isNotNull();
      final Set<String> names = new TreeSet<>();
      for (final String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
        final String name = line.strip();
        if (!name.isEmpty() && !name.startsWith("#")) {
          names.add(name);
        }
      }
      return names;
    }
  }
}
