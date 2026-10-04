package com.clavaris.identity.infrastructure.adapter.in.web;

/**
 * Joins the two values a phone number field posts (a country dial code and the local number) into
 * the one display string {@code Account.phoneNumber} stores — no validated PhoneNumber type exists,
 * so this keeps that concept out of the domain and command layer. Shared by every form that takes a
 * phone number (the profile's Personal information, the Users tab's "Create user").
 *
 * <p>A local number with no country code is treated as no phone number at all, never a number
 * missing its code (the code picker's own first option is the blank placeholder).
 */
final class PhoneNumberInput {

  private PhoneNumberInput() {
    // Static helpers only.
  }

  /** {@code "+502 5555-0100"}, or {@code null} when either part is blank. */
  /* default */ static String combine(final String countryCode, final String localNumber) {
    final String local = blankToNull(localNumber);
    final String code = blankToNull(countryCode);
    return local == null || code == null ? null : code + " " + local;
  }

  private static String blankToNull(final String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
