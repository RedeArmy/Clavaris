package com.clavaris.identity.application.usecase.getaccountavatar;

import com.clavaris.common.domain.model.BinaryContentEquality;

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
   * {@code byte[] content}'s own identity, not its bytes — overridden here via {@link
   * BinaryContentEquality} so two {@code Content} values with the same bytes actually compare
   * equal, same shared helper {@code PlatformAccountAvatarResult.Content}'s own identical override
   * uses (a SonarCloud "Duplicated Lines" finding on this exact hand-rolled block, previously only
   * silenced locally via a PMD CPD marker that SonarCloud's own duplication engine never honored).
   */
  record Content(byte[] content, String contentType) implements AccountAvatarResult {

    @Override
    public boolean equals(final Object other) {
      return this == other
          || (other instanceof Content(byte[] otherContent, String otherContentType)
              && BinaryContentEquality.contentEquals(
                  content, contentType, otherContent, otherContentType));
    }

    @Override
    public int hashCode() {
      return BinaryContentEquality.contentHashCode(content, contentType);
    }

    @Override
    public String toString() {
      return "Content[content="
          + BinaryContentEquality.describeContentLength(content)
          + ", contentType="
          + contentType
          + "]";
    }
  }
}
