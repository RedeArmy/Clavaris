# ADR-0033: Pause a one-time-code method after too many wrong codes in a row

**Status:** ✅ Approved (2026-10-29)

## Context

ADR-0024 §3/§6 added two 6-digit codes sent by email: the passwordless sign-in code and the
device-verification code. BR-ID-18 said each was "capped at 5 failed attempts per issued token". That
was never built as written. What existed was a rate limit on the sign-in code's confirmation: 10
requests per 5 minutes per address, counting successes too, forgetting everything when the window
moves on. The device-verification confirmation had **no limit at all**, and consumed its code with
a plain `consume()` then `save()`, the race TD-ARCH-041 had closed elsewhere.

A rate limit alone does not bound a patient guesser. Asking for a new code is allowed (5 per ten
minutes, one per 30 seconds), and every new code is a fresh random secret, so an attacker who stays
under the window can keep trying for ever: about 2,880 attempts a day against one address, roughly
0.3 % a day against a 6-digit numeric code and about 8 % over a month of sustained effort (my
arithmetic from the configured limits). SP 800-63B-4 asks for more: §3.1.3.2 requires a secret
under 64 bits to have its **consecutive** failed attempts effectively limited, and says generating a
new secret does not reset that count; §3.2.2 caps consecutive failures at 100 per authenticator and
account. A window that resets with time is not a count of consecutive failures.

## Decision

### 1. Count consecutive failures per subject, and pause the method at a limit

`CodeAttemptTracker` (a port, Redis in `app`) counts the wrong codes presented in a row for one
subject. The counter is cleared only by a right code (or after a day without any failure), never by
asking for a new code. At 30 consecutive failures
(`clavaris.code-lockout.max-consecutive-failures`) the method is paused for 15 minutes; each later
pause doubles (30 minutes, 1 hour, … up to 24 hours), the history is kept a week, and a right code
clears all of it. The 10-per-5-minutes rate limit stays as the burst limiter. The counting and the
decision to lock are one Lua script, so exactly one failure sees the counter reach the limit.

With the 30-bit format of ADR-0034 the daily exposure falls to a few attempts per billion; even with today's numeric format, once the pause has doubled up to its maximum, an
attacker is limited to about 30 attempts a day instead of about 2,880.

### 2. The subject is the address, not the account

The sign-in code is keyed by organization and **submitted address**, whether or not an account
exists. An unknown address is counted and paused exactly like a real one, and the paused answer is
the same for both, so neither the count nor the lock says whether an address has an account. (This
answers the reason ADR-0024 gave for not keeping a per-account counter: that no stable identifier
exists before the lookup.) The device-verification code is keyed by account, since whoever presents
it has already passed the password. Only a real account is audited and mailed. The key is hashed
(`RateLimitKeyHasher`) before it reaches Redis, so no address is stored in the clear.

### 3. What a pause does

- `AuthenticateWithEmailCodeService` and `ConfirmDeviceTrustChallengeService` refuse **even the right
  code** with `CodeTemporarilyLockedException` (a clear message on the page, not a bare "invalid").
- Asking for a sign-in code while paused sends nothing, silently, like the 30-second wait: a code
  could not be redeemed, and sending one only mails the owner a secret they cannot use.
- Only that method is paused. The person can still sign in with their password (BR-ID-12).

### 4. The owner is told

The failure that locks writes an audit event (`account.code_sign_in_locked`) and mails the address
of record how long the pause lasts, off the request thread on `emailRequestExecutor`. A failure to
send it never undoes the lock. A lock is an attack signal worth showing the victim.

### 5. The sign-in link

The link carries only an unguessable 256-bit token, so a wrong one cannot be tied to an address or
an account before the lookup, and brute force is infeasible. The same lock cannot apply; what does
is a burst limit per source IP on `POST /o/*/login/email-link/confirm` (30 per 5 minutes). Its
consumption was already made atomic (TD-SEC-075). The device-verification confirmation also gets the
same per-IP limit, having had none.

## Consequences

- **Positive.** The acknowledged brute-force gap is closed for both codes; the device-verification
  code, which had no limit, is covered; the lock does not leak which addresses have accounts; the
  owner learns of an attack.
- **Negative.** An attacker can pause the code method for a victim by failing on purpose, a
  denial of one optional method. Accepted: the victim can use their password, the pause is temporary
  and grows slowly, the owner is told, and it is audited. Redis being down fails open (wrong codes
  are not counted for that time), the call the rate limiter already makes (TD-SEC-022).
- **Not changed.** The 10-per-5-minutes limit on the confirmation, the 5-per-10-minutes limit on
  requesting codes, the code format (ADR-0034, decided separately).
