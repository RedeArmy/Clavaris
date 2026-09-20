package com.clavaris.identity.application.usecase.updateplatformaccountprofilepicture;

import com.clavaris.common.domain.model.BinaryContentEquality;
import com.clavaris.identity.domain.model.PlatformAccountId;
import java.util.Objects;

/**
 * {@code updateaccountprofilepicture.UpdateAccountProfilePictureCommand}'s platform-tier sibling.
 *
 * <p>SonarCloud: same {@code byte[]}-content equals/hashCode/toString override, via the same shared
 * {@link BinaryContentEquality} helper, as that command's own identical one — see its own Javadoc
 * for why.
 */
// PMD.LongVariable: otherPlatformAccountId (the equals() destructuring pattern variable) mirrors
// the record's own platformAccountId component name — matching it exactly reads better than an
// arbitrarily shortened one.
@SuppressWarnings("PMD.LongVariable")
public record UpdatePlatformAccountProfilePictureCommand(
    PlatformAccountId platformAccountId, byte[] content, String contentType) {

  @Override
  public boolean equals(final Object other) {
    if (this == other) {
      return true;
    }
    if (!(other
        instanceof
        UpdatePlatformAccountProfilePictureCommand(
            PlatformAccountId otherPlatformAccountId,
            byte[] otherContent,
            String otherContentType))) {
      return false;
    }
    return platformAccountId.equals(otherPlatformAccountId)
        && BinaryContentEquality.contentEquals(
            content, contentType, otherContent, otherContentType);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        platformAccountId, BinaryContentEquality.contentHashCode(content, contentType));
  }

  @Override
  public String toString() {
    return "UpdatePlatformAccountProfilePictureCommand[platformAccountId="
        + platformAccountId
        + ", content="
        + BinaryContentEquality.describeContentLength(content)
        + ", contentType="
        + contentType
        + "]";
  }
}
