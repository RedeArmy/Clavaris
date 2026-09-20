package com.clavaris.identity.application.usecase.getplatformaccountavatar;

import java.util.Arrays;

/** {@code getaccountavatar.AccountAvatarResult}'s platform-tier sibling. */
public sealed interface PlatformAccountAvatarResult {

  record Redirect(String externalUrl) implements PlatformAccountAvatarResult {}

  /**
   * SonarCloud: same {@code byte[]}-content equals/hashCode/toString override as {@code
   * AccountAvatarResult.Content}'s own identical one — see that record's own Javadoc for why.
   */
  record Content(byte[] content, String contentType) implements PlatformAccountAvatarResult {

    // CPD-OFF: genuinely irreducible duplication with AccountAvatarResult.Content's own
    // identical block — see that record's own identical CPD-OFF comment for why.
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
