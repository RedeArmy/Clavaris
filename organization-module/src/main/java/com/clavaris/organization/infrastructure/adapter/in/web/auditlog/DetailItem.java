package com.clavaris.organization.infrastructure.adapter.in.web.auditlog;

/**
 * One "label: value" pair of an audit event's detail, ready to show.
 *
 * @param label what the value is ("Client", "Role", "Requests per minute")
 * @param shown the value as people see it: a resolved name, Yes/No, or a long identifier cut short
 * @param full the complete value, offered as a tooltip when {@code shown} was cut or resolved
 * @param shortened {@code true} when {@code shown} differs from {@code full}
 */
public record DetailItem(String label, String shown, String full, boolean shortened) {

  public static DetailItem plain(final String label, final String value) {
    return new DetailItem(label, value, value, false);
  }
}
