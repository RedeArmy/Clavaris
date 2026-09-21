package com.clavaris.identity.application.usecase.getplatformaccountavatar;

import com.clavaris.common.domain.model.BinaryContentEquality;

/** {@code getaccountavatar.AccountAvatarResult}'s platform-tier sibling. */
public sealed interface PlatformAccountAvatarResult {

  record Redirect(String externalUrl) implements PlatformAccountAvatarResult {}

  /**
   * SonarCloud: same {@code byte[]}-content equals/hashCode/toString override, via the same shared
   * {@link BinaryContentEquality} helper, as {@code AccountAvatarResult.Content}'s own identical
   * one — see that record's own Javadoc for why.
   */
  record Content(byte[] content, String contentType) implements PlatformAccountAvatarResult {

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
