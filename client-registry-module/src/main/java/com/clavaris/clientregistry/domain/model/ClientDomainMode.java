package com.clavaris.clientregistry.domain.model;

/**
 * ADR-0009 §2: how a custom domain is fronted. {@code CNAME} is the recommended path (the consumer
 * points a CNAME at Clavaris, which terminates TLS via SNI) — {@code PROXY} is the consumer running
 * its own reverse proxy in front of Clavaris instead. Both are in scope for v1 (an explicit,
 * deliberate override of ADR-0009's own "leaning toward deferring Proxy" lean). {@code SHARED} (no
 * custom domain — Clavaris's own default host) is never a member of this enum: it's the implicit
 * state when no {@link ClientDomainConfig} row exists at all, same absence-of-row convention {@link
 * RedirectPolicy}/{@link ClientBranding} already establish.
 */
public enum ClientDomainMode {
  CNAME,
  PROXY
}
