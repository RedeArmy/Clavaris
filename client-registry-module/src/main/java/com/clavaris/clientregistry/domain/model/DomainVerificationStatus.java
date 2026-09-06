package com.clavaris.clientregistry.domain.model;

/**
 * ADR-0009 §2: the state of a {@link ClientDomainConfig}'s DNS TXT-record ownership challenge. Only
 * {@code VERIFIED} ever makes a client embedding-eligible in production (BR-CLIENT-04) — a newly
 * requested or re-requested domain always starts {@code PENDING}, and a failed lookup moves it to
 * {@code FAILED} rather than deleting the row, so the operator can see the last attempt's outcome
 * and retry without re-entering the hostname/mode.
 */
public enum DomainVerificationStatus {
  PENDING,
  VERIFIED,
  FAILED
}
