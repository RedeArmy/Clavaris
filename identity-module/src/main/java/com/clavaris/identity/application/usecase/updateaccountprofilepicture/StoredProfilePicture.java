package com.clavaris.identity.application.usecase.updateaccountprofilepicture;

import com.clavaris.common.domain.model.BinaryContentEquality;

/**
 * {@link ProfilePictureStorage#download}'s return shape — raw bytes plus the content type the
 * avatar-serving endpoint must echo back as this response's own {@code Content-Type}.
 *
 * <p>SonarCloud: a record's auto-generated {@code equals}/{@code hashCode}/{@code toString} use
 * {@code byte[] content}'s own identity, not its bytes — overridden here via {@link
 * BinaryContentEquality} so two values with the same bytes actually compare equal, same shared
 * helper every other {@code byte[]}-content record across this module uses.
 */
public record StoredProfilePicture(byte[] content, String contentType) {

  @Override
  public boolean equals(final Object other) {
    return this == other
        || (other instanceof StoredProfilePicture(byte[] otherContent, String otherContentType)
            && BinaryContentEquality.contentEquals(
                content, contentType, otherContent, otherContentType));
  }

  @Override
  public int hashCode() {
    return BinaryContentEquality.contentHashCode(content, contentType);
  }

  @Override
  public String toString() {
    return "StoredProfilePicture[content="
        + BinaryContentEquality.describeContentLength(content)
        + ", contentType="
        + contentType
        + "]";
  }
}
