package com.clavaris.identity.infrastructure.adapter.out.security;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * TD-SEC-102: lists the Organizations that have a key file in the store's directory and removes
 * one, for the sweep that deletes the files of Organizations that no longer exist. Kept apart from
 * {@link SigningKeyStore}, which persists and retrieves keys; this only reads file names and ages.
 *
 * <p>The naming ({@code signing-keys-org-<uuid>.p12}) is the one {@code SigningKeyStore.fileFor}
 * writes; a file that does not follow it is not one of ours to judge and is left alone. Only files
 * older than {@code olderThan} are listed: a new Organization's file is written before the
 * transaction that creates its row commits.
 */
final class OrganizationKeyFiles {

  private static final String PREFIX = "signing-keys-org-";
  private static final String SUFFIX = ".p12";

  private OrganizationKeyFiles() {}

  /**
   * Removes an Organization's whole file whatever it holds, and reports whether there was one. The
   * file is the Organization's own (TD-SEC-054), so no list of kids is needed and a second call is
   * a no-op. Nothing else writes it once the Organization is being erased.
   */
  /* package */ static boolean delete(final Path baseDir, final UUID organizationId) {
    try {
      return Files.deleteIfExists(baseDir.resolve(PREFIX + organizationId + SUFFIX));
    } catch (final IOException e) {
      throw new UncheckedIOException("Failed to remove the key store of " + organizationId, e);
    }
  }

  /* package */ static List<UUID> idsOlderThan(final Path baseDir, final Duration olderThan) {
    final Instant cutoff = Instant.now().minus(olderThan);
    final List<UUID> found = new ArrayList<>();
    try (DirectoryStream<Path> files = Files.newDirectoryStream(baseDir, PREFIX + "*" + SUFFIX)) {
      for (final Path file : files) {
        final String name = file.getFileName().toString();
        final String id = name.substring(PREFIX.length(), name.length() - SUFFIX.length());
        if (Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)) {
          parse(id).ifPresent(found::add);
        }
      }
    } catch (final IOException e) {
      throw new UncheckedIOException("Failed to list the key stores in " + baseDir, e);
    }
    return found;
  }

  private static Optional<UUID> parse(final String text) {
    try {
      return Optional.of(UUID.fromString(text));
    } catch (final IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
