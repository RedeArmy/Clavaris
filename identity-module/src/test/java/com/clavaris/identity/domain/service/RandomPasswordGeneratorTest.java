package com.clavaris.identity.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RandomPasswordGeneratorTest {

  @Test
  void generatesA32CharacterValue() {
    String generated = RandomPasswordGenerator.generate();

    assertThat(generated).hasSize(32);
  }

  @Test
  void satisfiesThePasswordPolicyItsOwnCallerRelinquishesOn() {
    // ADR-0024 §5: the whole point of this class — the generated value must itself pass
    // PasswordPolicy, the same rule a real user-submitted password is checked against.
    String generated = RandomPasswordGenerator.generate();

    assertThat(PasswordPolicy.isSatisfiedBy(generated)).isTrue();
  }

  @Test
  void neverGeneratesTheSameValueTwiceInARealisticSample() {
    long distinctValues =
        IntStream.range(0, 100)
            .mapToObj(i -> RandomPasswordGenerator.generate())
            .distinct()
            .count();

    assertThat(distinctValues).isEqualTo(100);
  }
}
