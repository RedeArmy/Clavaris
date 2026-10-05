package com.clavaris.common.domain.model;

/**
 * TD-ARCH-028 (closed): the same two- and three-state status-badge CSS-class ternary was
 * hand-copied across 8 Thymeleaf templates in 3 different modules — a third delivery status, or a
 * badge-class rename, needed editing 2-8 files in lockstep. Lives in {@code common}, not a single
 * owning module, same "every module's own templates need to reach it" reasoning {@link
 * LocalizedDateFormatter} already documents — templates invoke it via SpringEL's {@code
 * T(com.clavaris.common.domain.model.StatusBadgeCssClass).forBoolean(...)}/{@code
 * .forDeliveryStatus(...)} syntax, which needs no dialect registration (same reason that class
 * stays dialect-free too).
 */
public final class StatusBadgeCssClass {

  private StatusBadgeCssClass() {
    // utility class, never instantiated
  }

  /**
   * The common "active/enabled" vs. "everything else" two-state badge — {@code OAuthClient#
   * active()}, {@code WebhookEndpoint#active()}, {@code PlatformClient} secret-key expiry, and
   * {@code Organization}'s own production-vs-non-production environment badge all share this exact
   * shape; the caller resolves its own boolean first (e.g. {@code environment().name() ==
   * 'PRODUCTION'}), this only maps the result to a CSS class.
   *
   * @param positive whichever boolean the caller's own badge condition already evaluates to
   * @return {@code clavaris-badge--success} if {@code positive}, {@code clavaris-badge--muted}
   *     otherwise
   */
  public static String forBoolean(final boolean positive) {
    return positive ? "clavaris-badge--success" : "clavaris-badge--muted";
  }

  /**
   * {@code WebhookDelivery}'s own three-state status badge — {@code common} has no Maven dependency
   * on webhook-module's own {@code WebhookDeliveryStatus} enum, so this takes the already-resolved
   * {@code .name()} string every call site already had in hand.
   *
   * @param statusName {@code delivery.status().name()} — {@code SUCCEEDED}, {@code FAILED}, {@code
   *     EXHAUSTED}, or {@code PENDING}
   * @return {@code clavaris-badge--success} for {@code SUCCEEDED}, {@code clavaris-badge--error}
   *     for {@code FAILED}/{@code EXHAUSTED}, {@code clavaris-badge--muted} otherwise
   */
  // PMD.OnlyOneReturn: three genuinely distinct outcomes — same "each needs its own exit"
  // rationale this codebase's other three-way status mappers already establish.
  // PMD.AvoidLiteralsInIfCondition: these are the real, fixed WebhookDeliveryStatus enum names
  // (SUCCEEDED/FAILED/EXHAUSTED) this method exists specifically to branch on — spelled out
  // literally is the whole point, same rationale this codebase's other status-name comparisons
  // already document for an identical case.
  @SuppressWarnings({"PMD.OnlyOneReturn", "PMD.AvoidLiteralsInIfCondition"})
  public static String forDeliveryStatus(final String statusName) {
    if ("SUCCEEDED".equals(statusName)) {
      return "clavaris-badge--success";
    }
    if ("FAILED".equals(statusName) || "EXHAUSTED".equals(statusName)) {
      return "clavaris-badge--error";
    }
    return "clavaris-badge--muted";
  }
}
