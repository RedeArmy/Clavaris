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
    try {
      final Attributes attributes =
          new InitialDirContext(env).getAttributes(fqdn, new String[] {"TXT"});
      return readTxtValues(attributes.get("TXT"));
    } catch (final NamingException e) {
      // BR-DATA-01: the hostname being verified is operator-supplied configuration, not PII — safe
      // to log; the exception's own message is a DNS-resolution detail, not a secret.
      LOG.warn("DNS TXT lookup failed for {}: {}", fqdn, e.getMessage());
      return List.of();
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
