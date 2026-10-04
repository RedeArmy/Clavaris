package com.clavaris.common.domain.model.auditlog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuditDetailFormatterTest {

  private static List<DetailItem> format(final String detail) {
    return AuditDetailFormatter.format(detail, AuditDetailFormatter.Names.none());
  }

  @Test
  void aDeletedClientBecomesALabelledShortenedValueWithTheFullIdOnHover() {
    final List<DetailItem> items =
        format("deletedClientId=sk_test_example0-0000-0000-0000-000000000000");

    assertEquals(1, items.size());
    assertEquals("Client", items.get(0).label());
    assertEquals("sk_test_example0…", items.get(0).shown());
    assertEquals("sk_test_example0-0000-0000-0000-000000000000", items.get(0).full());
    assertTrue(items.get(0).shortened());
  }

  @Test
  void aValueRunsToTheNextKeySoANameWithSpacesSurvives() {
    final List<DetailItem> items =
        format("organizationId=11111111-1111-1111-1111-111111111111 name=Senior reviewer");

    assertEquals(1, items.size(), "the organization's own id is left out");
    assertEquals("Name", items.get(0).label());
    assertEquals("Senior reviewer", items.get(0).shown());
    assertFalse(items.get(0).shortened());
  }

  @Test
  void commaSeparatedPairsAreSplitAndTheTrailingCommaDropped() {
    final List<DetailItem> items = format("hostname=login.acme.test, organizationId=abc");

    assertEquals(1, items.size());
    assertEquals("Hostname", items.get(0).label());
    assertEquals("login.acme.test", items.get(0).shown());
  }

  @Test
  void booleansBecomeYesAndNoAndListsLoseTheirBrackets() {
    final List<DetailItem> items = format("enabled=true providers=[GOOGLE, GITHUB]");

    assertEquals("Yes", items.get(0).shown());
    assertEquals("GOOGLE, GITHUB", items.get(1).shown());
    assertEquals("Providers", items.get(1).label());
  }

  @Test
  void anEmptyListReadsNone() {
    assertEquals("None", format("providers=[]").get(0).shown());
  }

  @Test
  void roleAndWorkspaceIdsAreReplacedByTheirNamesWhenTheyStillExist() {
    final AuditDetailFormatter.Names names =
        new AuditDetailFormatter.Names(
            Map.of("ws-1", "Hiring"), Map.of("role-a", "Admin", "role-b", "Recruiter"));

    final List<DetailItem> items =
        AuditDetailFormatter.format(
            "workspaceId=ws-1 previousRoleId=role-a newRoleId=role-b", names);

    assertEquals("Workspace", items.get(0).label());
    assertEquals("Hiring", items.get(0).shown());
    assertTrue(items.get(0).shortened(), "the id stays available as a tooltip");
    assertEquals("ws-1", items.get(0).full());
    assertEquals("Previous role", items.get(1).label());
    assertEquals("Admin", items.get(1).shown());
    assertEquals("New role", items.get(2).label());
    assertEquals("Recruiter", items.get(2).shown());
  }

  @Test
  void anIdThatNoLongerResolvesIsShownShortInsteadOfRaw() {
    final List<DetailItem> items = format("roleId=8cf9ba3f-c6ad-44bf-9795-43b3b87e5116");

    assertEquals("Role", items.get(0).label());
    assertEquals("8cf9ba3f-c6ad-44…", items.get(0).shown());
  }

  @Test
  void anUnknownKeyIsSpelledOutAsWords() {
    assertEquals("Email verification", format("emailVerificationMethod=LINK").get(0).label());
    assertEquals("Some new field", format("someNewField=x").get(0).label());
  }

  @Test
  void nothingComesOutOfNullBlankOrNullValuedDetails() {
    assertTrue(format(null).isEmpty());
    assertTrue(format("   ").isEmpty());
    assertTrue(format("name=null").isEmpty());
  }

  @Test
  void aShortValueIsNotTouched() {
    final DetailItem item = format("requestsPerMinute=120").get(0);

    assertEquals("Requests per minute", item.label());
    assertEquals("120", item.shown());
    assertFalse(item.shortened());
  }
}
