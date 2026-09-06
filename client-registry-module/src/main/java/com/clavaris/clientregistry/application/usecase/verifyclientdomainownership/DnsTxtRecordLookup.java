package com.clavaris.clientregistry.application.usecase.verifyclientdomainownership;

import java.util.List;

/**
 * Outbound port for the actual DNS TXT-record lookup ADR-0009 §2 requires to prove hostname
 * ownership. A real, network-bound operation — this port exists precisely so {@link
 * VerifyClientDomainOwnershipService} never depends on JDK DNS APIs directly, same "domain never
 * touches infrastructure concerns" rule every other outbound port in this module follows.
 *
 * <p>Returns an empty list for "no TXT record published at this name" (NXDOMAIN, or a name that
 * resolves but carries no TXT records) — that is a normal, retryable outcome, not a failure.
 * Anything that keeps this port from completing the lookup at all (DNS server unreachable, a
 * timeout) is deliberately swallowed by the infrastructure adapter into the same empty result: from
 * an operator's perspective, either case means "verification failed, retry later," and v1 has no
 * need to distinguish "domain is misconfigured" from "DNS infrastructure is flaky" (both collapse
 * to {@link com.clavaris.clientregistry.domain.model.DomainVerificationStatus#FAILED}).
 */
@FunctionalInterface
public interface DnsTxtRecordLookup {

  List<String> lookupTxtRecords(String fqdn);
}
