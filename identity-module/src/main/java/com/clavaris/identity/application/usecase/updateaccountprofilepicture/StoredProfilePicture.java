package com.clavaris.identity.application.usecase.updateaccountprofilepicture;

import java.util.Arrays;

/**
 * {@link ProfilePictureStorage#download}'s return shape — raw bytes plus the content type the
 * avatar-serving endpoint must echo back as this response's own {@code Content-Type}.
 *
 * <p>SonarCloud: a record's auto-generated {@code equals}/{@code hashCode}/{@code toString} use
 * {@code byte[] content}'s own identity, not its bytes — overridden here so two values with the
 * same bytes actually compare equal. {@code toString} deliberately prints the array's length, not
 * {@link Arrays#toString(byte[])}'s own raw byte dump — an image's own pixel data has no debugging
 * value spelled out as a list of numbers, and could be sizable.
 */
public record StoredProfilePicture(byte[] content, String contentType) {

  @Override
  public boolean equals(final Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof StoredProfilePicture(byte[] otherContent, String otherContentType))) {
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
    return "StoredProfilePicture[content=byte["
        + content.length
        + "], contentType="
        + contentType
        + "]";
  }
}
