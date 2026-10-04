package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PhoneNumberInputTest {

  @Test
  void joinsTheCountryCodeAndTheLocalNumber() {
    assertThat(PhoneNumberInput.combine("+502", "5555-0100")).isEqualTo("+502 5555-0100");
  }

  @Test
  void trimsBothParts() {
    assertThat(PhoneNumberInput.combine(" +1 ", "  212 555 0100 ")).isEqualTo("+1 212 555 0100");
  }

  @Test
  void aLocalNumberWithNoCountryCodeIsNoPhoneNumberAtAll() {
    assertThat(PhoneNumberInput.combine("", "5555-0100")).isNull();
    assertThat(PhoneNumberInput.combine(null, "5555-0100")).isNull();
  }

  @Test
  void aCountryCodeWithNoNumberIsNoPhoneNumberAtAll() {
    assertThat(PhoneNumberInput.combine("+502", " ")).isNull();
    assertThat(PhoneNumberInput.combine("+502", null)).isNull();
  }
}
