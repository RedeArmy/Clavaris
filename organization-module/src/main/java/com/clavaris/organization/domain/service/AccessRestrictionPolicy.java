package com.clavaris.organization.domain.service;

import com.clavaris.organization.domain.model.AccessRestrictionEntry;
import com.clavaris.organization.domain.model.RestrictionType;
import java.util.List;
import java.util.Locale;

/**
 * SDE-III review, 2026-09-19 — Clerk "Restrictions" parity: pure evaluation, no framework
 * dependency, no I/O, same split every other domain service in this codebase (e.g. {@code
 * identity.domain.service.PasswordPolicy}) already establishes between "the rule" and "where the
 * data it reads comes from."
 *
 * <p>Allowlist wins outright the moment any {@code ALLOWLIST} entry exists for the Organization —
 * matches Clerk's own documented behavior ("Allowlist ... when enabled, blocklist identifiers are
 * ignored"), not a Clavaris-invented interaction. An Organization with zero restriction entries at
 * all (the default, every existing Organization today) allows everything — this policy is opt-in,
 * never a silent new gate on registrations that never asked for one.
 */
// PMD.OnlyOneReturn: isAllowed's allowlist-wins early exit and matches' domain-vs-exact branch are
// each two genuinely distinct outcomes, not a single flow forced into two exits — same rationale
// every other early-return chain in this codebase documents.
@SuppressWarnings("PMD.OnlyOneReturn")
public final class AccessRestrictionPolicy {

  private AccessRestrictionPolicy() {}

  public static boolean isAllowed(final List<AccessRestrictionEntry> entries, final String email) {
    final String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
    final List<AccessRestrictionEntry> allowlist =
        entriesOfType(entries, RestrictionType.ALLOWLIST);
    if (!allowlist.isEmpty()) {
      return matchesAny(allowlist, normalizedEmail);
    }
    final List<AccessRestrictionEntry> blocklist =
        entriesOfType(entries, RestrictionType.BLOCKLIST);
    return !matchesAny(blocklist, normalizedEmail);
  }

  private static List<AccessRestrictionEntry> entriesOfType(
      final List<AccessRestrictionEntry> entries, final RestrictionType type) {
    return entries.stream().filter(entry -> entry.type() == type).toList();
  }

  private static boolean matchesAny(
      final List<AccessRestrictionEntry> entries, final String normalizedEmail) {
    return entries.stream().anyMatch(entry -> matches(entry, normalizedEmail));
  }

  private static boolean matches(final AccessRestrictionEntry entry, final String normalizedEmail) {
    if (entry.isDomainPattern()) {
      return normalizedEmail.endsWith(entry.identifier());
    }
    return normalizedEmail.equals(entry.identifier());
  }
}
