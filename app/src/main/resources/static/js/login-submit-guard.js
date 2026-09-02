/**
 * Code review finding (2026-09-01): stops a second tab of THIS SAME BROWSER from submitting this
 * page's own login form while another tab's submission from that same browser is still in flight
 * — see KnownDevice's own Javadoc ("two concurrent logins... producing two rows and two
 * notifications for what's really one physical device") for the server-side finding this exists to
 * soften, and its own reasoning for why no server-side fix was possible there instead.
 *
 * Deliberately does nothing more ambitious than this: a lock scoped to this origin's own
 * localStorage, which only this browser's own tabs can ever read or write — an attacker's browser
 * is a categorically different origin's storage and can never participate in or spoof this
 * coordination, so this cannot reintroduce the timing-correlation hole TD-SEC-033 closed. It only
 * ever helps the "same browser, two tabs" case; two genuinely different devices logging in at the
 * same moment still notify independently, which is correct.
 */
(function () {
  "use strict";

  var LOCK_KEY = "clavaris_login_submit_lock";
  // Generous relative to a real login round trip, not a real user's own wait between clicks — only
  // needs to outlive "browser has sent the request, hasn't yet navigated or re-rendered".
  var LOCK_TTL_MS = 5000;

  function lockIsHeld() {
    var raw;
    try {
      raw = window.localStorage.getItem(LOCK_KEY);
    } catch (err) {
      // Storage blocked (private mode, disabled, quota) degrades to "no coordination" — the
      // pre-existing, accepted double-notification behavior, never a broken login.
      return false;
    }
    if (!raw) {
      return false;
    }
    var acquiredAt = parseInt(raw, 10);
    return !isNaN(acquiredAt) && Date.now() - acquiredAt < LOCK_TTL_MS;
  }

  function acquireLock() {
    try {
      window.localStorage.setItem(LOCK_KEY, String(Date.now()));
    } catch (err) {
      // Same degrade-safely reasoning as lockIsHeld() above.
    }
  }

  function releaseStaleLock() {
    // This page just (re)loaded — a fresh visit, or the server-rendered re-render after a failed
    // attempt — either way, whatever race this lock was guarding against is already over.
    try {
      window.localStorage.removeItem(LOCK_KEY);
    } catch (err) {
      // Nothing to degrade — the TTL above is the real safety net regardless.
    }
  }

  document.addEventListener("DOMContentLoaded", function () {
    releaseStaleLock();

    var form = document.querySelector("form[data-login-form]");
    if (!form) {
      return;
    }

    form.addEventListener("submit", function (event) {
      if (lockIsHeld()) {
        event.preventDefault();
        return;
      }
      acquireLock();
    });
  });
})();
