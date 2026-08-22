# ADR-0011: Resend for transactional email delivery

**Status:** ✅ Aprobado

## Context

Email verification and password recovery (`prd-mvp.md` §2, BR-ID-04/BR-ID-05) both require actually delivering an email — TD-SEC-004 tracked the gap where the registration UI promised a verification link that no code ever sent, because no mail-sending mechanism existed in any module. `.env.example` had provisioned SMTP variable names as a placeholder, but nothing implemented against them.

## Decision

Email delivery goes through **Resend's HTTP API** (`https://api.resend.com/emails`), not SMTP. The sending domain (`MAIL_FROM_ADDRESS`) is provisioned and DNS-verified with Resend against a domain managed on Zoho — an operational/DNS decision made outside this codebase, orthogonal to the code itself. `ResendMailSender` (`identity-module/infrastructure/adapter/out/mail/`) implements the `MailSender` port with a plain `java.net.http.HttpClient` call, not the official Resend SDK — a single POST endpoint doesn't justify an extra dependency, consistent with this codebase's existing outbound-HTTP integrations.

## Consequences

- **Positive:** closes TD-SEC-004 — registration (and password recovery) now really deliver the email they promise, verified end to end against a real HTTP endpoint in `EmailVerificationAndPasswordResetIntegrationTest` (Resend itself intercepted by a test double, never called for real in CI).
- **Positive:** no SMTP credential/connection management (auth, TLS, connection pooling) to own — a single API key (`RESEND_API_KEY`) and one HTTP call.
- **Negative:** a hard dependency on Resend's availability for two user-facing flows; no fallback provider exists in v1. `MailDeliveryException` surfaces the failure to the caller rather than silently dropping it, but there is no retry/queue mechanism yet — a transient Resend outage means a real, visible failure for the user requesting verification/reset at that moment.
- **Negative:** `.env.example`'s SMTP placeholder was wrong from the start (never implemented) — a reminder that provisioning a config variable name is not the same as having decided the integration shape; this ADR is what that decision actually looks like once made.

## Alternatives considered

- **SMTP (via Spring's `JavaMailSender` or similar)** — the original placeholder in `.env.example`. Rejected: more moving parts to configure and operate correctly (host, port, TLS mode, auth) for no benefit over a single HTTP API call, and Resend's API is what the operator already intends to provision against a Zoho-managed sending domain.
- **The official Resend Java SDK** — rejected for v1: one endpoint, one request shape; a plain `HttpClient` call is fewer lines than adding and learning a new dependency's API surface for a single use.
