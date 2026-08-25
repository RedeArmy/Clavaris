package com.clavaris.common.application.port;

/**
 * TD-FUT-011 / ADR-0015: the outbound port every business module's use-case services call to record
 * a security-relevant event as a real, alertable metric — not just a log line depending on someone
 * watching it (the exact gap this row named: {@code event=rate_limit_fail_open}, {@code
 * event=refresh_token_reuse_detected}, {@code event=login_failure} all existed as structured logs
 * long before anything counted them). Same "shared port, colocated implementation" precedent as
 * {@link AuditEventRecorder} just above it — the threshold client-registry-module's own {@code
 * pom.xml} already names ("refactor into a shared utility once a third module needs the same
 * thing") is what justifies this living in {@code common}, not duplicated per module.
 *
 * <p>One generic method, not one per event, so a new security event never requires a new interface
 * method — mirrors {@code AuditEventRecorder}'s own generic shape. Metric names follow this
 * project's existing {@code event=xxx} structured-log convention 1:1 (e.g. {@code
 * event=login_failure} → {@code clavaris.auth.login} with an {@code outcome=failure} tag), so a log
 * line and its corresponding counter are always trivially correlatable by anyone reading either.
 *
 * @param metricName a dotted, stable Micrometer metric name, e.g. {@code "clavaris.auth.login"}
 * @param tagKeyValuePairs an even-length list of tag key/value pairs (e.g. {@code "tier",
 *     "organization", "outcome", "failure"}) — never a raw PII/credential value (BR-DATA-01 extends
 *     to metric tags too: a Prometheus label is exactly as durable and exportable as a log line,
 *     and high-cardinality tags — an email, an accountId — would also blow up Prometheus's own
 *     storage, a second, independent reason never to pass one here)
 */
@FunctionalInterface
public interface SecurityMetricsRecorder {

  void increment(String metricName, String... tagKeyValuePairs);
}
