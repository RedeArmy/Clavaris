package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.webhook.domain.model.WebhookEventContract;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * TD-ARCH-064 (ADR-0052): the JSON a webhook consumer receives is the JSON of the event record the
 * outbox stored, so renaming a field of a Java record used to change a published contract without
 * anyone deciding to. This test serialises every event record of every module (found on the class
 * path, so a new event is covered the day it is written) with the application's own Jackson
 * configuration and compares the result with the sample checked in under {@code
 * src/test/resources/webhook-contracts/v<version>/}.
 *
 * <p>What to do when it fails:
 *
 * <ul>
 *   <li>a field was <b>added</b>: not a new version (receivers ignore fields they do not know).
 *       Update the sample and review it: {@code mvn -pl app test -Dtest=WebhookPayloadContractTest
 *       -Dwebhook.contract.update=true}.
 *   <li>a field was <b>removed, renamed, retyped or changed in meaning</b>: a new version. Raise
 *       {@link WebhookEventContract#CURRENT_VERSION}, generate the new directory the same way, keep
 *       delivering the old shape for the deprecation period and say so in the release notes ({@code
 *       docs/03-architecture/api-contracts/webhook-contract.md}).
 *   <li>an event was <b>deleted</b>: remove its sample (the test names it).
 * </ul>
 */
class WebhookPayloadContractTest {

  private static final Path CONTRACTS =
      Path.of(
          "src",
          "test",
          "resources",
          "webhook-contracts",
          "v" + WebhookEventContract.CURRENT_VERSION);
  private static final boolean UPDATE = Boolean.getBoolean("webhook.contract.update");
  private static final List<String> EVENT_PACKAGES =
      List.of(
          "com.clavaris.identity.domain.event",
          "com.clavaris.organization.domain.event",
          "com.clavaris.clientregistry.domain.event");

  // The mapper the application builds, not one assembled by hand: Spring Boot's own
  // auto-configuration
  // is what serialises the outbox payload in production.
  private static ObjectMapper applicationMapper() {
    final ObjectMapper[] holder = new ObjectMapper[1];
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
        .run(context -> holder[0] = context.getBean(ObjectMapper.class));
    return holder[0];
  }

  private static List<Class<?>> eventRecords() throws ClassNotFoundException {
    final ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false) {
          @Override
          protected boolean isCandidateComponent(
              final org.springframework.beans.factory.annotation.AnnotatedBeanDefinition
                  definition) {
            return true;
          }
        };
    scanner.addIncludeFilter((reader, factory) -> true);
    final List<Class<?>> records = new ArrayList<>();
    for (final String pkg : EVENT_PACKAGES) {
      for (final BeanDefinition definition : scanner.findCandidateComponents(pkg)) {
        final Class<?> type = Class.forName(definition.getBeanClassName());
        if (type.isRecord()) {
          records.add(type);
        }
      }
    }
    records.sort((a, b) -> a.getSimpleName().compareTo(b.getSimpleName()));
    return records;
  }

  // A value for every component type an event record uses today. A type this does not know fails
  // the
  // test with its name, which is the prompt to teach it (and a prompt to ask whether a consumer
  // should
  // be handed that type at all).
  private static Object sample(final Type type) throws ReflectiveOperationException {
    final Class<?> raw =
        type instanceof ParameterizedType parameterized
            ? (Class<?>) parameterized.getRawType()
            : (Class<?>) type;
    if (raw == UUID.class) {
      return UUID.fromString("00000000-0000-4000-8000-000000000001");
    }
    if (raw == Instant.class) {
      return Instant.parse("2026-01-02T03:04:05Z");
    }
    if (raw == String.class) {
      return "sample";
    }
    if (raw == boolean.class || raw == Boolean.class) {
      return true;
    }
    if (raw == int.class || raw == Integer.class) {
      return 1;
    }
    if (raw == long.class || raw == Long.class) {
      return 1L;
    }
    if (raw.isEnum()) {
      return raw.getEnumConstants()[0];
    }
    if (Collection.class.isAssignableFrom(raw)) {
      return raw == Set.class ? Set.of() : List.of();
    }
    if (Map.class.isAssignableFrom(raw)) {
      return Map.of();
    }
    if (raw == Optional.class) {
      return Optional.empty();
    }
    if (raw.isRecord()) {
      return newRecord(raw);
    }
    throw new IllegalStateException(
        "WebhookPayloadContractTest has no sample for " + raw.getName() + "; add one");
  }

  private static Object newRecord(final Class<?> type) throws ReflectiveOperationException {
    final RecordComponent[] components = type.getRecordComponents();
    final Class<?>[] types = new Class<?>[components.length];
    final Object[] values = new Object[components.length];
    for (int i = 0; i < components.length; i++) {
      types[i] = components[i].getType();
      values[i] = sample(components[i].getGenericType());
    }
    final Constructor<?> constructor = type.getDeclaredConstructor(types);
    constructor.setAccessible(true);
    return constructor.newInstance(values);
  }

  @Test
  void everyEventRecordSerialisesToItsPinnedShape() throws Exception {
    final ObjectMapper mapper = applicationMapper();
    final List<Class<?>> records = eventRecords();
    assertThat(records).as("the event records found on the class path").hasSizeGreaterThan(30);
    Files.createDirectories(CONTRACTS);

    final List<String> problems = new ArrayList<>();
    for (final Class<?> type : records) {
      final String actual =
          mapper.writerWithDefaultPrettyPrinter().writeValueAsString(newRecord(type)) + "\n";
      final Path file = CONTRACTS.resolve(type.getSimpleName() + ".json");
      if (UPDATE) {
        Files.writeString(file, actual, StandardCharsets.UTF_8);
        continue;
      }
      if (!Files.exists(file)) {
        problems.add(
            type.getSimpleName()
                + ": no pinned shape. Generate it with -Dwebhook.contract.update=true and review it.");
        continue;
      }
      final JsonNode expected = mapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
      if (!expected.equals(mapper.readTree(actual))) {
        problems.add(
            type.getSimpleName()
                + ": the JSON changed.\n  pinned: "
                + expected
                + "\n  now:    "
                + mapper.readTree(actual)
                + "\n  An added field keeps version "
                + WebhookEventContract.CURRENT_VERSION
                + " (update the sample); a removed, renamed or retyped field is a new version"
                + " (WebhookEventContract).");
      }
    }
    assertThat(problems).as("webhook payload contract").isEmpty();
  }

  @Test
  void noPinnedShapeIsLeftForAnEventThatNoLongerExists() throws Exception {
    final Set<String> existing = new TreeSet<>();
    for (final Class<?> type : eventRecords()) {
      existing.add(type.getSimpleName() + ".json");
    }
    final List<String> stale = new ArrayList<>();
    try (Stream<Path> files = Files.exists(CONTRACTS) ? Files.list(CONTRACTS) : Stream.empty()) {
      files
          .map(path -> path.getFileName().toString())
          .filter(n -> !existing.contains(n))
          .forEach(stale::add);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    assertThat(stale).as("samples with no event record (delete them)").isEmpty();
  }
}
