package com.clavaris.identity.application.usecase.starttotpenrollment;

/**
 * What the person needs to add the app: the secret in groups to type, and the {@code otpauth://}
 * URI a QR code or a link carries. Shown once per setup and never logged.
 */
public record TotpEnrollmentStart(String secret, String otpauthUri) {

  @Override
  public String toString() {
    return "TotpEnrollmentStart[secret=<redacted>, otpauthUri=<redacted>]";
  }
}
