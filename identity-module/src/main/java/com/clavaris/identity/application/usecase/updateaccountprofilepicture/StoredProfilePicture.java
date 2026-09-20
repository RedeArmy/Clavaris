package com.clavaris.identity.application.usecase.updateaccountprofilepicture;

/**
 * {@link ProfilePictureStorage#download}'s return shape — raw bytes plus the content type the
 * avatar-serving endpoint must echo back as this response's own {@code Content-Type}.
 */
public record StoredProfilePicture(byte[] content, String contentType) {}
