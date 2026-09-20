package com.clavaris.identity.application.usecase.getaccountavatar;

import java.util.Arrays;

/**
 * {@link GetAccountAvatarUseCase}'s own two real shapes (ADR-0026): {@link Redirect} for a social
 * provider's own external picture URL (captured once at registration, never re-hosted — the browser
 * fetches it directly from that provider's own CDN), {@link Content} for everything Clavaris itself
 * serves the bytes of — a real Supabase-stored upload, or {@code GetAccountAvatarService}'s own
 * generated-initials default when no picture is set at all. The stable {@code
 * /o/{organizationId}/avatars/{accountId}} URL is identical either way; only what happens once a
 * request reaches it differs.
 */
public sealed interface AccountAvatarResult {

  record Redirect(String externalUrl) implements AccountAvatarResult {}

  /**
   * SonarCloud: a record's auto-generated {@code equals}/{@code hashCode}/{@code toString} use
   * {@code byte[] content}'s own identity, not its bytes — overridden here so two {@code Content}
   * values with the same bytes actually compare equal, same convention {@link
   * com.clavaris.identity.application.usecase.updateaccountprofilepicture.StoredProfilePicture}'s
   * own identical override already establishes. {@code toString} deliberately prints the array's
   * length, not {@link Arrays#toString(byte[])}'s own raw byte dump — an image's own pixel data has
   * no debugging value spelled out as a list of numbers, and could be sizable.
   */
  record Content(byte[] content, String contentType) implements AccountAvatarResult {

    // CPD-OFF: genuinely irreducible duplication with PlatformAccountAvatarResult.Content's own
    // identical block — same (byte[], String) shape, but the two sealed interfaces belong to
    // deliberately separate bounded contexts (Account vs PlatformAccount, ADR-0010) that must
    // never share a domain type, so extracting a common helper would be the wrong fix here.
    @Override
    public boolean equals(final Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof Content(byte[] otherContent, String otherContentType))) {
        return false;
      }
      return Arrays.equals(content, otherContent) && contentType.equals(otherContentType);
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(content) * 31 + contentType.hashCode();
    }

    @Override
    public String toString() {
      return "Content[content=byte[" + content.length + "], contentType=" + contentType + "]";
    }
    // CPD-ON
  }
}
