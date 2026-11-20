package com.clavaris.clientregistry.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.clavaris.common.domain.model.InvalidInputException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// BR-ID-36: 60 to 3600 seconds, absent meaning 300.
/** TD-SEC-104 / ADR-0049. */
class AccessTokenLifetimeTest {

  @Test
  void theDefaultIsWhatEveryClientHadBeforeItCouldBeChosen() {
    assertThat(AccessTokenLifetime.DEFAULT_SECONDS).isEqualTo(300);
    assertThat(AccessTokenLifetime.effectiveSeconds(null)).isEqualTo(300);
  }

  @ParameterizedTest
  @ValueSource(ints = {60, 61, 300, 3_599, 3_600})
  void acceptsTheBoundsAndEverythingBetween(final int seconds) {
    assertThat(AccessTokenLifetime.requireAllowed(seconds)).isEqualTo(seconds);
    assertThat(AccessTokenLifetime.effectiveSeconds(seconds)).isEqualTo(seconds);
  }

  @Test
  void aNullIsAllowedAndMeansTheDefault() {
    assertThat(AccessTokenLifetime.requireAllowed(null)).isNull();
  }

  @ParameterizedTest
  @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 1, 59, 3_601, 86_400, Integer.MAX_VALUE})
  void refusesAnythingOutsideTheHardCapAsAClientError(final int seconds) {
    assertThatThrownBy(() -> AccessTokenLifetime.requireAllowed(seconds))
        .isInstanceOf(InvalidInputException.class)
        .hasMessageContaining("60")
        .hasMessageContaining("3600");
  }
}
