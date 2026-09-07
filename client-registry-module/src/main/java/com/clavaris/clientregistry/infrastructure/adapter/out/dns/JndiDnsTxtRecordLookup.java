package com.clavaris.clientregistry.infrastructure.adapter.out.dns;

import com.clavaris.clientregistry.application.usecase.verifyclientdomainownership.DnsTxtRecordLookup;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * ADR-0009 §2: real DNS TXT-record lookup via the JDK's own built-in JNDI DNS provider — no new
 * dependency, same "vetted framework, not hand-rolled" posture CLAUDE.md §1 requires even for a
 * small infrastructure adapter like this one.
 *
 * <p>Every failure mode (NXDOMAIN, no TXT records, a timeout, the DNS server itself being
 * unreachable) is caught here and turned into an empty list — see {@link DnsTxtRecordLookup}'s own
 * Javadoc for why v1 deliberately doesn't distinguish "no such record" from "lookup failed."
 *
 * <p>TD-PERF-008 (closed): two real findings from an SDE-III performance review, fixed together
 * since both touch this same short method. <b>No explicit timeout</b> — without {@code
 * com.sun.jndi.dns.timeout.*}, an unresponsive DNS server ties up one admin-request thread for the
 * JDK provider's own undocumented-in-this-codebase default (an unresponsive server can otherwise
 * hang this call for tens of seconds across the provider's own retry/backoff schedule) — bounded
 * here to a low single-digit-seconds worst case instead. Still admin-triggered only ({@code
 * VerifyClientDomainOwnershipService}, ADR-0009 §2), never a hot path any real end user touches, so
 * a generous-but-bounded timeout is the right trade, not an aggressive one. <b>A real resource
 * leak</b> — the constructed {@code InitialDirContext} was never closed on either the success or
 * the {@code NamingException} path. Not a try-with-resources fix: confirmed live (a real {@code
 * javac} compile attempt), not assumed from the register row's own text, that {@code
 * InitialDirContext} does <em>not</em> implement {@link AutoCloseable} despite declaring a {@code
 * close()} method — {@code javax.naming.Context} predates that interface. Fixed with an explicit
 * {@code try/finally}, closing quietly (a close failure here is never worth failing an otherwise-
 * successful lookup over, and is itself logged rather than silently swallowed).
 */
// PMD.AtLeastOneConstructor: this class holds no state of its own beyond the static LOG field —
// same "intentionally empty" precedent GlobalExceptionHandler's own identical suppression
// documents. PMD.LooseCoupling/ReplaceHashtableWithMap: javax.naming.Context's own constructor
// requires exactly Hashtable<?, ?>, not Map — a JDK API constraint, not a design choice this class
// is free to change.
@SuppressWarnings({"PMD.AtLeastOneConstructor", "PMD.LooseCoupling", "PMD.ReplaceHashtableWithMap"})
@Component
class JndiDnsTxtRecordLookup implements DnsTxtRecordLookup {

  private static final Logger LOG = LoggerFactory.getLogger(JndiDnsTxtRecordLookup.class);

  // TD-PERF-008: 3s initial + up to 2 retries — a bounded, single-digit-seconds worst case for an
  // admin-triggered, non-hot-path call, comfortably below what an operator would tolerate as "the
  // verify button is broken" but generous enough for a real, slow-but-legitimate DNS server.
  // PMD.LongVariable: these name exactly the two com.sun.jndi.dns.timeout.* properties they
  // configure — same "the exact spec/API term, not arbitrarily long" precedent OAuthClient's own
  // postLogoutRedirectUris suppression already establishes.
  @SuppressWarnings("PMD.LongVariable")
  private static final String DNS_TIMEOUT_INITIAL_MS = "3000";

  @SuppressWarnings("PMD.LongVariable")
  private static final String DNS_TIMEOUT_RETRIES = "2";

  // Two exits (a real result, or an empty list on any failure) is clearer here than forcing a
  // single-return shape onto "succeeded" vs. "failed" — same rationale ClientDomainConfig's own
  // validateHostnameIfPresent suppression documents. PMD.GuardLogStatement: the logged hostname
  // and exception message are both cheap accessors already computed for this catch block, not an
  // expensive call this rule's "avoid unconditional work" concern applies to.
  @Override
  @SuppressWarnings({"PMD.OnlyOneReturn", "PMD.GuardLogStatement"})
  public List<String> lookupTxtRecords(final String fqdn) {
    final Hashtable<String, String> env = new Hashtable<>();
    env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
    env.put("com.sun.jndi.dns.timeout.initial", DNS_TIMEOUT_INITIAL_MS);
    env.put("com.sun.jndi.dns.timeout.retries", DNS_TIMEOUT_RETRIES);
    InitialDirContext context = null;
    try {
      context = new InitialDirContext(env);
      final Attributes attributes = context.getAttributes(fqdn, new String[] {"TXT"});
      return readTxtValues(attributes.get("TXT"));
    } catch (final NamingException e) {
      // BR-DATA-01: the hostname being verified is operator-supplied configuration, not PII — safe
      // to log; the exception's own message is a DNS-resolution detail, not a secret.
      LOG.warn("DNS TXT lookup failed for {}: {}", fqdn, e.getMessage());
      return List.of();
    } finally {
      closeQuietly(context, fqdn);
    }
  }

  // TD-PERF-008: InitialDirContext doesn't implement AutoCloseable (confirmed live, see this
  // class's own Javadoc), so this is an explicit finally-block close, not try-with-resources.
  // Deliberately never rethrows or fails the caller over a close error — the actual lookup already
  // either succeeded or was already handled by the caller's own NamingException catch by the time
  // this runs; a leaked-but-otherwise-idle JNDI context outliving this method a little longer is a
  // strictly smaller problem than turning a successful lookup into a failed one.
  @SuppressWarnings("PMD.GuardLogStatement") // same rationale as lookupTxtRecords' own identical
  // suppression above — both arguments are cheap accessors.
  private static void closeQuietly(final InitialDirContext context, final String fqdn) {
    if (context == null) {
      return;
    }
    try {
      context.close();
    } catch (final NamingException e) {
      LOG.warn("Failed to close DNS lookup context for {}: {}", fqdn, e.getMessage());
    }
  }

  // PMD.OnlyOneReturn: same rationale as lookupTxtRecords above. PMD.LawOfDemeter:
  // txtAttribute.getAll() is the standard javax.naming.directory.Attribute API shape for reading
  // every value off one attribute, not a foreign object graph walk.
  @SuppressWarnings({"PMD.OnlyOneReturn", "PMD.LawOfDemeter"})
  private List<String> readTxtValues(final Attribute txtAttribute) throws NamingException {
    if (txtAttribute == null) {
      return List.of();
    }
    final List<String> values = new ArrayList<>();
    final NamingEnumeration<?> all = txtAttribute.getAll();
    while (all.hasMore()) {
      // Each TXT value arrives quoted (RFC 1035 <character-string> literal form) — strip the
      // surrounding quotes so the caller compares against the raw challenge token it minted.
      values.add(String.valueOf(all.next()).replace("\"", ""));
    }
    return values;
  }
}
