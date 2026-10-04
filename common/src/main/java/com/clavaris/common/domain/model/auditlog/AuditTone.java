package com.clavaris.common.domain.model.auditlog;

import java.util.Locale;

/**
 * How an audit event reads at a glance, driving a coloured marker beside it: something was added,
 * changed or removed, a credential was touched ({@link #SENSITIVE}) or something went wrong ({@link
 * #WARNING}).
 */
public enum AuditTone {
  CREATED,
  UPDATED,
  DELETED,
  SENSITIVE,
  WARNING;

  /** The CSS modifier, e.g. {@code created}. */
  public String css() {
    return name().toLowerCase(Locale.ROOT);
  }
}
