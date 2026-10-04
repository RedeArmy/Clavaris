package com.clavaris.organization.infrastructure.adapter.in.web.auditlog;

import com.clavaris.organization.domain.model.Workspace;
import com.clavaris.organization.domain.model.WorkspaceRole;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the {@code key=value key=value} string the application writes into an audit event's detail
 * and turns it into labelled, human-sized pairs: known keys get a proper label, ids of workspaces
 * and roles are replaced by their names when they still exist, booleans become Yes/No, lists lose
 * their brackets, long identifiers are cut short (the full value stays available as a tooltip), and
 * keys that only repeat what the page already says (this Organization's own id, its owner) are left
 * out. A value may contain spaces ({@code name=Senior reviewer}), so a value runs up to the next
 * {@code key=} rather than to the next space.
 */
public final class AuditDetailFormatter {

  private static final Pattern KEY = Pattern.compile("(?<![A-Za-z0-9])([A-Za-z][A-Za-z0-9]*)=");
  private static final int MAX_SHOWN = 20;
  private static final int SHOWN_PREFIX = 16;
  private static final String ELLIPSIS = "…";
  private static final String TRUE_TEXT = "true";
  private static final String FALSE_TEXT = "false";

  // Keys that repeat what the page already says: this Organization's own id and its owner.
  private static final Set<String> HIDDEN = Set.of("organizationId", "ownerPlatformAccountId");

  private static final Map<String, String> LABELS =
      Map.ofEntries(
          Map.entry("clientId", "Client"),
          Map.entry("deletedClientId", "Client"),
          Map.entry("endpointId", "Endpoint"),
          Map.entry("deletedEndpointId", "Endpoint"),
          Map.entry("provider", "Provider"),
          Map.entry("providers", "Providers"),
          Map.entry("enabled", "Enabled"),
          Map.entry("requestsPerMinute", "Requests per minute"),
          Map.entry("name", "Name"),
          Map.entry("hostname", "Hostname"),
          Map.entry("reason", "Reason"),
          Map.entry("emailVerificationMethod", "Email verification"),
          Map.entry("newKid", "New key"),
          Map.entry("previousKid", "Previous key"),
          Map.entry("purgedKid", "Purged key"),
          Map.entry("replacementKid", "Replacement key"),
          Map.entry("developmentOrganizationId", "Development environment"),
          Map.entry("workspaceId", "Workspace"),
          Map.entry("roleId", "Role"),
          Map.entry("previousRoleId", "Previous role"),
          Map.entry("newRoleId", "New role"));

  /** What the formatter may look names up in. */
  public record Names(Map<String, String> workspaces, Map<String, String> roles) {

    public static Names none() {
      return new Names(Map.of(), Map.of());
    }

    /** Names of the workspaces and roles that still exist, keyed by id. */
    public static Names existing(
        final List<Workspace> workspaces, final List<WorkspaceRole> roles) {
      final Map<String, String> workspaceNames = new HashMap<>();
      workspaces.forEach(
          workspace -> workspaceNames.put(workspace.id().toString(), workspace.name()));
      final Map<String, String> roleNames = new HashMap<>();
      roles.forEach(role -> roleNames.put(role.id().toString(), role.name()));
      return new Names(workspaceNames, roleNames);
    }
  }

  private AuditDetailFormatter() {
    // Static helpers only.
  }

  public static List<DetailItem> format(final String detail, final Names names) {
    final List<DetailItem> items = new ArrayList<>();
    final Matcher matcher = KEY.matcher(detail == null ? "" : detail);
    final List<int[]> spans = new ArrayList<>();
    final List<String> keys = new ArrayList<>();
    while (matcher.find()) {
      keys.add(matcher.group(1));
      spans.add(new int[] {matcher.start(), matcher.end()});
    }
    for (int index = 0; index < keys.size(); index++) {
      final int valueEnd = index + 1 < keys.size() ? spans.get(index + 1)[0] : detail.length();
      final String value = trimValue(detail.substring(spans.get(index)[1], valueEnd));
      final String key = keys.get(index);
      if (!HIDDEN.contains(key) && !value.isEmpty() && !"null".equals(value)) {
        items.add(item(key, value, names));
      }
    }
    return items;
  }

  /** The label for a key, or the key itself spelled as words ("someKey" becomes "Some key"). */
  public static String labelFor(final String key) {
    return LABELS.getOrDefault(key, spelledOut(key));
  }

  private static DetailItem item(final String key, final String value, final Names names) {
    final String label = labelFor(key);
    final String resolved = resolve(key, value, names);
    final String cleaned = clean(resolved);
    final String shown = cleaned.length() > MAX_SHOWN ? shorten(cleaned) : cleaned;
    // The tooltip carries the full original only when something was swapped for a name or cut.
    return new DetailItem(label, shown, value, !resolved.equals(value) || !shown.equals(cleaned));
  }

  private static String resolve(final String key, final String value, final Names names) {
    final boolean workspace = "workspaceId".equals(key);
    final boolean role = key.toLowerCase(Locale.ROOT).endsWith("roleid");
    String resolved = value;
    if (workspace) {
      resolved = names.workspaces().getOrDefault(value, value);
    } else if (role) {
      resolved = names.roles().getOrDefault(value, value);
    }
    return resolved;
  }

  private static String clean(final String value) {
    String cleaned = value;
    if (TRUE_TEXT.equals(value)) {
      cleaned = "Yes";
    } else if (FALSE_TEXT.equals(value)) {
      cleaned = "No";
    } else if (value.startsWith("[") && value.endsWith("]")) {
      cleaned = value.substring(1, value.length() - 1).strip();
    }
    return cleaned.isEmpty() ? "None" : cleaned;
  }

  // Only an unbroken token (an id, a key id, a client id) is cut; a sentence is left whole.
  private static String shorten(final String value) {
    return value.chars().anyMatch(Character::isWhitespace)
        ? value
        : value.substring(0, SHOWN_PREFIX) + ELLIPSIS;
  }

  private static String trimValue(final String raw) {
    String value = raw.strip();
    if (value.endsWith(",")) {
      value = value.substring(0, value.length() - 1).strip();
    }
    return value;
  }

  private static String spelledOut(final String key) {
    final String words = key.replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
    return words.substring(0, 1).toUpperCase(Locale.ROOT) + words.substring(1);
  }
}
