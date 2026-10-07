package com.clavaris.identity.infrastructure.adapter.out.mail;

import com.clavaris.identity.domain.model.SocialProvider;

/** How a value is shown inside an email: a provider by its brand, a missing value as a dash. */
final class EmailValues {

  private static final String UNKNOWN = "—";

  private EmailValues() {
    // Static helpers only.
  }

  // The provider is shown by its brand name, never as the enum constant (GOOGLE, GITHUB).
  /* default */ static String providerName(final SocialProvider provider) {
    return switch (provider) {
      case GOOGLE -> "Google";
      case GITHUB -> "GitHub";
    };
  }

  // A request header can be absent or blank; the email then shows a dash rather than "null".
  /* default */ static String orUnknown(final String value) {
    return value == null || value.isBlank() ? UNKNOWN : value.strip();
  }
}
