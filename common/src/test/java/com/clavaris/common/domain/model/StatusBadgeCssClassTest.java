package com.clavaris.common.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StatusBadgeCssClassTest {

  @Test
  void forBooleanReturnsSuccessWhenTrue() {
    assertThat(StatusBadgeCssClass.forBoolean(true)).isEqualTo("clavaris-badge--success");
  }

  @Test
  void forBooleanReturnsMutedWhenFalse() {
    assertThat(StatusBadgeCssClass.forBoolean(false)).isEqualTo("clavaris-badge--muted");
  }

  @Test
  void forDeliveryStatusReturnsSuccessForSucceeded() {
    assertThat(StatusBadgeCssClass.forDeliveryStatus("SUCCEEDED"))
        .isEqualTo("clavaris-badge--success");
  }

  @Test
  void forDeliveryStatusReturnsErrorForFailed() {
    assertThat(StatusBadgeCssClass.forDeliveryStatus("FAILED")).isEqualTo("clavaris-badge--error");
  }

  @Test
  void forDeliveryStatusReturnsErrorForExhausted() {
    assertThat(StatusBadgeCssClass.forDeliveryStatus("EXHAUSTED"))
        .isEqualTo("clavaris-badge--error");
  }

  @Test
  void forDeliveryStatusReturnsMutedForAnythingElse() {
    assertThat(StatusBadgeCssClass.forDeliveryStatus("PENDING")).isEqualTo("clavaris-badge--muted");
  }
}
