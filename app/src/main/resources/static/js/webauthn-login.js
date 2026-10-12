/**
 * TD-FUT-034, Clerk "View Profile" passkeys parity — wires up login.html's own hidden-by-default
 * "Sign in with a passkey" button. Hidden in markup, not gated by a server-side policy flag: a
 * passkey is a property of the Account that registered one, never an Organization-wide on/off
 * switch (see AccountAuthenticationPolicySnapshot's own Javadoc for why no field was added there
 * for this feature) — this script is the one place "does this browser actually support WebAuthn"
 * gets decided, by feature detection alone.
 *
 * Uses the WebAuthn Level 3 JSON convenience methods (PublicKeyCredential.parseRequestOptionsFromJSON,
 * credential.toJSON()) rather than hand-rolled base64url<->ArrayBuffer conversion. The server sends
 * Yubico's own AssertionRequest#toCredentialsGetJson() shape ({"publicKey": {...}}), which this
 * script unwraps before calling parseRequestOptionsFromJSON() — see that call site's own comment,
 * real bug found live, 2026-10-11.
 */
(function () {
  "use strict";

  // Translation helper: the shared i18n.js when the page loads it, plain English otherwise.
  var t = function (text) {
    return window.clavarisI18n ? window.clavarisI18n.t(text) : text;
  };

  var BUTTON_ID = "passkey-signin-button";
  var ERROR_ID = "passkey-signin-error";

  if (!window.PublicKeyCredential || !PublicKeyCredential.parseRequestOptionsFromJSON) {
    return;
  }

  var button = document.getElementById(BUTTON_ID);
  if (!button) {
    return;
  }
  button.hidden = false;

  function csrfHeaders(extra) {
    var tokenInput = document.getElementById("webauthn-csrf-token");
    var headerInput = document.getElementById("webauthn-csrf-header");
    var headers = extra || {};
    if (tokenInput && headerInput && headerInput.value) {
      headers[headerInput.value] = tokenInput.value;
    }
    return headers;
  }

  var GENERIC_ERROR = "Something went wrong signing in with your passkey.";
  var INTERNAL_ERROR_CODES = ["start-failed", "finish-failed"];

  function showError(message) {
    var errorElement = document.getElementById(ERROR_ID);
    if (!errorElement) {
      return;
    }
    var isInternalCode = message && INTERNAL_ERROR_CODES.indexOf(message) !== -1;
    errorElement.textContent = message && !isInternalCode ? message : t(GENERIC_ERROR);
    errorElement.hidden = false;
  }

  button.addEventListener("click", function () {
    button.disabled = true;
    var clientId = button.getAttribute("data-client-id") || null;
    var redirectUrl = button.getAttribute("data-redirect-url") || null;

    fetch("login/webauthn/start", { method: "POST", headers: csrfHeaders() })
      .then(function (response) {
        if (!response.ok) {
          throw new Error("start-failed");
        }
        return response.json();
      })
      .then(function (optionsJson) {
        // Real bug found live, 2026-10-11: the server returns Yubico's own
        // AssertionRequest#toCredentialsGetJson() shape, {"publicKey": {...}} — convenient for
        // navigator.credentials.get() directly, but parseRequestOptionsFromJSON() (the WebAuthn
        // Level 3 convenience method) wants the UNWRAPPED PublicKeyCredentialRequestOptionsJSON
        // (challenge at the top level), not this one extra level of nesting. Unwrap here rather
        // than on the server: PublicKeyCredentialRequestOptions has no plain toJson() of its own
        // to switch to (unlike the registration side's PublicKeyCredentialCreationOptions).
        var options = PublicKeyCredential.parseRequestOptionsFromJSON(optionsJson.publicKey);
        return navigator.credentials.get({ publicKey: options });
      })
      .then(function (assertion) {
        return fetch("login/webauthn/finish", {
          method: "POST",
          headers: csrfHeaders({ "Content-Type": "application/json" }),
          body: JSON.stringify({
            credential: JSON.stringify(assertion.toJSON()),
            clientId: clientId,
            redirectUrl: redirectUrl,
          }),
        });
      })
      .then(function (response) {
        return response.json().then(function (data) {
          if (!response.ok) {
            throw new Error(data.error || "finish-failed");
          }
          window.location.href = data.redirectTo;
        });
      })
      .catch(function (err) {
        button.disabled = false;
        showError(err && err.message);
      });
  });
})();
