package com.clavaris.organization.infrastructure.adapter.in.web.auditlog;

import java.util.List;

/**
 * One row of the Audit Log as a person reads it. Everything technical (the raw action key, the
 * target type and id, the raw detail string, the actor id) is kept in the {@code raw*} fields,
 * shown only inside a collapsed "Technical details" disclosure for support and debugging.
 *
 * @param whenRelative "3 hours ago", "Yesterday", or the date for anything older
 * @param whenFull the exact moment, for the tooltip
 * @param whenIso the machine-readable timestamp for the {@code <time>} element
 * @param actorLabel who did it: "You", "Operator (localdev)", ...
 * @param actorIsYou whether the signed-in platform account did it
 * @param label what happened, as a sentence ("Secret Key deleted")
 * @param category the group it belongs to, for the filter and the small tag
 * @param tone how it reads at a glance, for the coloured marker
 * @param details the readable "label: value" pairs
 */
public record AuditLogEntryView(
    String whenRelative,
    String whenFull,
    String whenIso,
    String actorLabel,
    boolean actorIsYou,
    String label,
    AuditCategory category,
    AuditTone tone,
    List<DetailItem> details,
    String rawActor,
    String rawAction,
    String rawTarget,
    String rawDetail) {}
