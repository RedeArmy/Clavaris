package com.clavaris.organization.domain.model;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The image an Organization's consuming application is shown with: on its sign-in and consent pages
 * and, through them, everywhere its people see it.
 *
 * <p>Small by design (at most {@link #MAX_BYTES}) and limited to the four formats browsers draw
 * without help: PNG, JPEG, WebP and GIF. SVG is deliberately not one of them: an SVG is a document
 * that can carry script, and this one is served to the public. The declared content type is not
 * trusted: the file's own first bytes must match it, so a script renamed {@code logo.png} is
 * refused rather than stored and served.
 */
// PMD.AvoidFieldNameMatchingMethodName/ShortMethodName/LongVariable: same value-object
// conventions Organization's own identical suppression documents; of() is the factory name
// every domain class here uses.
@SuppressWarnings({
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.ShortMethodName",
  "PMD.LongVariable"
})
public final class OrganizationLogo {

  public static final int MAX_BYTES = 1024 * 1024;

  private static final String WEBP = "image/webp";

  private static final Map<String, byte[]> SIGNATURES =
      Map.of(
          "image/png", new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'},
          "image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF},
          "image/gif", new byte[] {'G', 'I', 'F', '8'});

  private final UUID organizationId;
  private final String contentType;
  private final byte[] content;

  private OrganizationLogo(
      final UUID organizationId, final String contentType, final byte[] content) {
    this.organizationId = organizationId;
    this.contentType = contentType;
    this.content = content.clone();
  }

  /**
   * A logo, validated.
   *
   * @throws IllegalArgumentException when it is empty, too large, not one of the accepted types, or
   *     its bytes are not of the type it claims to be
   */
  public static OrganizationLogo of(
      final UUID organizationId, final String declaredContentType, final byte[] bytes) {
    final String type = normalise(declaredContentType);
    requireUsableSize(bytes);
    requireAcceptedType(type);
    requireMatchingSignature(type, bytes);
    return new OrganizationLogo(organizationId, type, bytes);
  }

  /** A logo as it was persisted: already validated when it was stored. */
  public static OrganizationLogo reconstitute(
      final UUID organizationId, final String contentType, final byte[] content) {
    return new OrganizationLogo(organizationId, contentType, content);
  }

  public UUID organizationId() {
    return organizationId;
  }

  public String contentType() {
    return contentType;
  }

  public byte[] content() {
    return content.clone();
  }

  private static String normalise(final String declared) {
    final String type = declared == null ? "" : declared.strip().toLowerCase(Locale.ROOT);
    final int parameters = type.indexOf(';');
    return parameters < 0 ? type : type.substring(0, parameters).strip();
  }

  private static void requireUsableSize(final byte[] bytes) {
    if (bytes == null || bytes.length == 0) {
      throw new IllegalArgumentException("The logo is empty");
    }
    if (bytes.length > MAX_BYTES) {
      throw new IllegalArgumentException("The logo must not be larger than 1 MB");
    }
  }

  private static void requireAcceptedType(final String type) {
    if (!SIGNATURES.containsKey(type) && !WEBP.equals(type)) {
      throw new IllegalArgumentException("The logo must be a PNG, JPEG, WebP or GIF image");
    }
  }

  private static void requireMatchingSignature(final String type, final byte[] bytes) {
    if (!matchesSignature(type, bytes)) {
      throw new IllegalArgumentException("The file is not a valid " + type + " image");
    }
  }

  private static boolean matchesSignature(final String type, final byte[] bytes) {
    final boolean matches;
    if (WEBP.equals(type)) {
      // RIFF <4 bytes of length> WEBP
      matches =
          bytes.length >= 12
              && Arrays.equals(Arrays.copyOfRange(bytes, 0, 4), new byte[] {'R', 'I', 'F', 'F'})
              && Arrays.equals(Arrays.copyOfRange(bytes, 8, 12), new byte[] {'W', 'E', 'B', 'P'});
    } else {
      final byte[] signature = SIGNATURES.get(type);
      matches =
          bytes.length >= signature.length
              && Arrays.equals(Arrays.copyOfRange(bytes, 0, signature.length), signature);
    }
    return matches;
  }
}
