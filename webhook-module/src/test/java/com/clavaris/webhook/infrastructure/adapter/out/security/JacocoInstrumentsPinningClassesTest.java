package com.clavaris.webhook.infrastructure.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * TD-TEST-020: the JDK loads the {@code InetAddressResolverProvider} service the first time {@code
 * InetAddress} is used, and if anything does that while the JaCoCo agent is still starting the
 * pinning classes load before it can instrument them. They then run uninstrumented and the coverage
 * report reads 0% for the classes the SSRF guard depends on (TD-SEC-053), although their tests
 * pass. The pom gives the agent a fixed session id, which removes the host-name lookup that did it;
 * this fails if the classes ever lose their instrumentation again.
 */
class JacocoInstrumentsPinningClassesTest {

  private static boolean jacocoAgentIsAttached() {
    return ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
        .anyMatch(argument -> argument.startsWith("-javaagent:") && argument.contains("jacoco"));
  }

  private static boolean isInstrumented(final Class<?> type) {
    // JaCoCo adds this synthetic method to every class it instruments.
    return Arrays.stream(type.getDeclaredMethods())
        .anyMatch(method -> "$jacocoInit".equals(method.getName()));
  }

  @Test
  void thePinningClassesAreInstrumentedWhenTheAgentIsAttached() {
    assumeTrue(
        jacocoAgentIsAttached(), "no JaCoCo agent on this JVM (an IDE run), nothing to check");

    assertThat(isInstrumented(PinnedAddresses.class))
        .as("PinnedAddresses was loaded before the agent could instrument it")
        .isTrue();
    assertThat(isInstrumented(PinningInetAddressResolverProvider.class))
        .as("PinningInetAddressResolverProvider was loaded before the agent could instrument it")
        .isTrue();
  }
}
