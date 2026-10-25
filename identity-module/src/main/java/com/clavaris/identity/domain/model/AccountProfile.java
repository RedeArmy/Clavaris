package com.clavaris.identity.domain.model;

/**
 * The optional, display/contact attributes of an {@link Account} (Clerk dashboard "Users" parity,
 * ADR-0026). Every component is nullable — email stays the one mandatory identity field (BR-ID-01)
 * — and none carries a domain invariant beyond "arbitrary text", so there are no dedicated types
 * the way {@link Email}/{@link Username} have. Immutable: each change returns a new value.
 */
public record AccountProfile(
    String firstName, String lastName, String phoneNumber, String pictureUrl) {

  public static final AccountProfile EMPTY = new AccountProfile(null, null, null, null);

  /** Always overwrites; a blank value is the caller's job to normalise to {@code null}. */
  /* default */ AccountProfile withName(final String newFirstName, final String newLastName) {
    return new AccountProfile(newFirstName, newLastName, phoneNumber, pictureUrl);
  }

  /* default */ AccountProfile withPhoneNumber(final String newPhoneNumber) {
    return new AccountProfile(firstName, lastName, newPhoneNumber, pictureUrl);
  }

  /* default */ AccountProfile withPictureUrl(final String newPictureUrl) {
    return new AccountProfile(firstName, lastName, phoneNumber, newPictureUrl);
  }

  /** Reverts to the generated-initials default avatar (ADR-0026). */
  /* default */ AccountProfile withoutPicture() {
    return new AccountProfile(firstName, lastName, phoneNumber, null);
  }

  /**
   * Non-destructive: only fills a component that is still {@code null}. A social provider's claims
   * (ADR-0026 / BR-ID-09 sibling decision) must never overwrite something a person already set.
   */
  /* default */ AccountProfile filledFrom(
      final String socialFirstName, final String socialLastName, final String socialPictureUrl) {
    return new AccountProfile(
        keep(firstName, socialFirstName),
        keep(lastName, socialLastName),
        phoneNumber,
        keep(pictureUrl, socialPictureUrl));
  }

  private static String keep(final String existing, final String candidate) {
    return existing == null ? candidate : existing;
  }
}
