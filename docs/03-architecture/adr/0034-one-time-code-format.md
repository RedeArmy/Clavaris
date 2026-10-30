# ADR-0034: One-time codes are six characters from a 32-symbol alphabet

**Status:** ✅ Approved (2026-10-29)

## Context

ADR-0024 fixed the passwordless sign-in code, the device-verification code and the email
verification code at six decimal digits: 1,000,000 possibilities, about 20 bits. SP 800-63B-4
§3.1.3.2 allows a secret of "at least six decimal digits (or equivalent)" but requires effective
limits on consecutive failures when it is under 64 bits (ADR-0033). The format still decides how
much work a guesser has between pauses, and how many mistakes honest people make typing it.

## Decision

A code is **six characters** drawn from `23456789ABCDEFGHJKLMNPQRSTUVWXYZ`: the digits 2 to 9 and
the capital letters without `I` and `O`.

- **32 symbols, exactly 5 bits each, 30 bits in all**: 1,073,741,824 codes, about a thousand times
  as many as six digits. 32 divides the range of `SecureRandom.nextInt` exactly, so every symbol is
  equally likely with no modulo bias, which an alphabet of 31 or 33 symbols would not give.
- **No pair a person can confuse.** `0` and `1` are removed along with `O` and `I`, the letters they
  are taken for, so none of the four can appear in a code. A letter kept next to a digit it looks
  like (`B/8`, `S/5`, `Z/2`, `G/6`) is accepted: removing those as well would leave an alphabet of 28
  or fewer symbols, under 30 bits, and the email shows the code in a large, spaced face.
- **Capitals only**, so there is no case to get wrong. `EmailOneTimeCode.normalize` accepts what
  people really type: lower case, spaces, hyphens and the dashes a word processor makes of them. It
  changes nothing else, so a value that is not a code matches nothing. A code issued before this
  decision (six digits) is unchanged by it and keeps working until it expires.
- The pages use the text keyboard, ask the browser not to capitalise, correct or spell-check, and
  show capitals while typing (`clavaris-code-input`); the server does not rely on any of that.

The same generator serves the sign-in code, the device-verification code and the email
verification code, so all three change together.

## Consequences

- **Positive.** A thousand times the search space per attempt, which with the consecutive-failure
  pause of ADR-0033 puts a month of sustained guessing at a few chances in a hundred thousand
  (about 30 attempts a day once the pause has doubled to its maximum, against 2^30 codes; my
  arithmetic). Fewer typing mistakes from lookalike characters.
- **Negative.** The phone's numeric keypad is no longer offered, so a code takes more taps to type.
  I could not confirm whether iOS and Android offer a code from a mail for autofill when it
  contains letters, so it is not relied on (`autocomplete="one-time-code"` is kept as a hint).
- **Found, not changed.** The email verification code has no page to type it into in Clavaris: the
  only confirmation route takes a link token. Its format changed with the generator, but nothing
  here makes it usable, which is a gap of its own.

## Addendum, 2026-10-29: a code is stored under a hash of the account and the code

`verification_tokens.token_hash` is unique across the whole table, and until now a code was hashed on
its own. With 30 bits, a second account that drew the same code as any earlier one (live, or among the
consumed rows that are kept) made its `INSERT` fail: the sign-in code was dropped silently, and the
device challenge, which is issued inside the sign-in request, failed the request. The chance rises
with every code ever issued. The sign-in code and the device-verification code are now stored and
looked up under `EmailOneTimeCode.hashFor(accountId, code)`; both places that confirm one already know
the account. A clash now needs one account to draw the same code twice. The email verification code is stored the same way once its page asks for the address (ADR-0036).
Codes in flight at the moment of deployment stop working (10 minutes at most).

