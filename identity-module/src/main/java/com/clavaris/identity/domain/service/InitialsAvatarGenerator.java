package com.clavaris.identity.domain.service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * ADR-0026: the generated-initials default avatar — shared between {@code GetAccountAvatarService}
 * (tenant {@code Account}) and {@code GetPlatformAccountAvatarService} ({@code PlatformAccount}),
 * extracted here once both needed the identical algorithm (CPD). Produces a small, hand-built SVG
 * (no third-party avatar-generation library needed for a handful of lines of XML) — never
 * user-uploaded content, so the same {@code image/svg+xml} script-injection concern {@code
 * UpdateAccountProfilePictureService}'s own content-type allow-list documents does not apply here.
 */
public final class InitialsAvatarGenerator {

  // A small, deliberately curated palette (not a raw hash-to-hex-color, which risks a muddy or
  // unreadable combination against the white initials text below) — the same "known-good fixed
  // set" reasoning as every other cosmetic default this codebase makes on a user's behalf without
  // asking.
  private static final List<String> PALETTE =
      List.of(
          "#F97066", "#F79009", "#7A5AF8", "#2E90FA", "#12B76A", "#EE46BC", "#0BA5EC", "#F63D68");

  private InitialsAvatarGenerator() {
    // Static helper only — no instance state.
  }

  /**
   * @param ownerId hashed to deterministically pick this owner's own color from {@link #PALETTE} —
   *     the same owner always gets the same color, different owners are spread across the palette.
   * @param firstName/{@code lastName} the owner's own profile fields, if set.
   * @param emailFallback used for the initial when neither name field is set — never blank, every
   *     Account/PlatformAccount has a mandatory email.
   */
  public static byte[] generateSvg(
      final UUID ownerId,
      final Optional<String> firstName,
      final Optional<String> lastName,
      final String emailFallback) {
    final String initials = initialsFor(firstName, lastName, emailFallback);
    final String color = PALETTE.get(Math.floorMod(ownerId.hashCode(), PALETTE.size()));
    final String svg =
        "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"256\" height=\"256\""
            + " viewBox=\"0 0 256 256\">"
            + "<rect width=\"256\" height=\"256\" fill=\""
            + color
            + "\"/>"
            + "<text x=\"50%\" y=\"50%\" dy=\".35em\" text-anchor=\"middle\""
            + " font-family=\"system-ui,sans-serif\" font-size=\"104\" font-weight=\"600\""
            + " fill=\"#ffffff\">"
            + initials
            + "</text>"
            + "</svg>";
    return svg.getBytes(StandardCharsets.UTF_8);
  }

  private static String initialsFor(
      final Optional<String> firstName,
      final Optional<String> lastName,
      final String emailFallback) {
    final String first = firstName.map(InitialsAvatarGenerator::firstChar).orElse("");
    final String last = lastName.map(InitialsAvatarGenerator::firstChar).orElse("");
    final String combined = (first + last).toUpperCase(Locale.ROOT);
    return combined.isEmpty() ? firstChar(emailFallback).toUpperCase(Locale.ROOT) : combined;
  }

  private static String firstChar(final String value) {
    return value.isBlank() ? "" : value.strip().substring(0, 1);
  }
}
