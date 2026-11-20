package com.clavaris.app.infrastructure.adapter.out.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import org.junit.jupiter.api.Test;

// TD-ARCH-057 / TD-ARCH-063: one failure pauses every further attempt, for the whole pause only.
class SessionStoreCircuitTest {

  @Test
  void startsClosedSoTheStoreIsTried() {
    assertThat(new SessionStoreCircuit(60_000).isOpen()).isFalse();
  }

  @Test
  void aFailureLeavesTheStoreAloneForThePause() {
    SessionStoreCircuit circuit = new SessionStoreCircuit(60_000);

    circuit.trip();

    assertThat(circuit.isOpen()).isTrue();
  }

  @Test
  void theStoreIsTriedAgainOnceThePauseHasPassed() {
    SessionStoreCircuit circuit = new SessionStoreCircuit(20);

    circuit.trip();

    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(() -> assertThat(circuit.isOpen()).isFalse());
  }
}
