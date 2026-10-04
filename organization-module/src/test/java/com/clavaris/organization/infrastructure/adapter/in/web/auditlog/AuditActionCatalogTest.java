package com.clavaris.organization.infrastructure.adapter.in.web.auditlog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class AuditActionCatalogTest {

  @Test
  void knownActionsReadAsASentenceWithTheirGroupAndTone() {
    final AuditActionCatalog.ActionInfo deleted =
        AuditActionCatalog.describe("organization_client.deleted");
    assertEquals("Secret Key deleted", deleted.label());
    assertEquals(AuditCategory.SECRET_KEYS, deleted.category());
    assertEquals(AuditTone.DELETED, deleted.tone());

    assertEquals(
        AuditTone.SENSITIVE, AuditActionCatalog.describe("oauth_client.secret_rotated").tone());
    assertEquals(
        AuditCategory.WEBHOOKS,
        AuditActionCatalog.describe("webhook_endpoint.url_updated").category());
    assertEquals(
        "Member's role changed",
        AuditActionCatalog.describe("workspace_membership.role_changed").label());
  }

  @Test
  void anActionNotInTheCatalogDegradesToAReadableSentenceNeverTheRawKey() {
    final AuditActionCatalog.ActionInfo info = AuditActionCatalog.describe("some_thing.was_done");

    assertEquals("Some thing was done", info.label());
    assertEquals(AuditCategory.OTHER, info.category());
    assertFalse(info.label().contains("_"));
    assertFalse(info.label().contains("."));
  }

  @Test
  void everyLabelIsAFinishedSentenceNotAKey() {
    for (final String action : AuditActionCatalog.knownActions()) {
      final String label = AuditActionCatalog.describe(action).label();
      assertFalse(label.contains("_"), action);
      assertTrue(Character.isUpperCase(label.charAt(0)), action);
    }
  }

  // Keeps the table honest: every action the application writes to the audit log must have a label.
  // Scans the sibling modules' sources (this test runs from the module directory); skipped, not
  // failed, if the repository layout is not there (e.g. the module is built on its own).
  @Test
  void everyActionTheApplicationWritesHasALabel() throws IOException {
    final Path modules = Path.of("..").toAbsolutePath().normalize();
    assumeTrue(
        Files.isDirectory(modules.resolve("identity-module")), "sibling modules not present");

    final Pattern written =
        Pattern.compile(
            "(?:\\.write\\(|AuditEvent\\.of\\()\\s*[^,;]+,\\s*\"([a-z_]+\\.[a-z_]+)\"",
            Pattern.DOTALL);
    final Set<String> missing = new TreeSet<>();
    final Set<String> known = new TreeSet<>();
    AuditActionCatalog.knownActions().forEach(known::add);
    int found = 0;
    try (Stream<Path> files = Files.walk(modules)) {
      for (final Path file :
          files
              .filter(path -> path.toString().endsWith(".java"))
              .filter(path -> path.toString().replace('\\', '/').contains("/src/main/java/"))
              .toList()) {
        final Matcher matcher = written.matcher(Files.readString(file));
        while (matcher.find()) {
          found++;
          if (!known.contains(matcher.group(1))) {
            missing.add(matcher.group(1));
          }
        }
      }
    }

    assertTrue(found > 40, "the scan should find the application's audit writes, found " + found);
    assertTrue(
        missing.isEmpty(), "audit actions without a label in AuditActionCatalog: " + missing);
  }
}
