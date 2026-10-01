/**
 * TD-FUT-034, Clerk "View Profile" passkeys parity — wires up identity/account/passkeys.html's own
 * "Register a new passkey" button. Same WebAuthn Level 3 JSON convenience methods as
 * webauthn-login.js (see its own header comment) — PublicKeyCredential.parseCreationOptionsFromJSON
 * / credential.toJSON(), not hand-rolled base64url<->ArrayBuffer conversion.
 */
(function () {
  "use strict";

  var BUTTON_ID = "register-passkey-button";
  var NICKNAME_ID = "passkey-nickname";
  var ERROR_ID = "passkey-registration-error";
  var GENERIC_ERROR = "Something went wrong registering your passkey.";
  var INTERNAL_ERROR_CODES = ["start-failed", "finish-failed"];

  if (!window.PublicKeyCredential || !PublicKeyCredential.parseCreationOptionsFromJSON) {
    var unsupportedNotice = document.getElementById("passkey-unsupported-notice");
    if (unsupportedNotice) {
      unsupportedNotice.hidden = false;
    }
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

  function showError(message) {
    var errorElement = document.getElementById(ERROR_ID);
    if (!errorElement) {
      return;
    }
    var isInternalCode = message && INTERNAL_ERROR_CODES.indexOf(message) !== -1;
    errorElement.textContent = message && !isInternalCode ? message : GENERIC_ERROR;
    errorElement.hidden = false;
  }

  button.addEventListener("click", function () {
    button.disabled = true;
    var nicknameInput = document.getElementById(NICKNAME_ID);
    var nickname = nicknameInput ? nicknameInput.value : null;

    fetch("passkeys/registration/start", { method: "POST", headers: csrfHeaders() })
      .then(function (response) {
        if (!response.ok) {
          throw new Error("start-failed");
        }
        return response.json();
      })
      .then(function (optionsJson) {
        var options = PublicKeyCredential.parseCreationOptionsFromJSON(optionsJson);
        return navigator.credentials.create({ publicKey: options });
      })
      .then(function (credential) {
        return fetch("passkeys/registration/finish", {
          method: "POST",
          headers: csrfHeaders({ "Content-Type": "application/json" }),
          body: JSON.stringify({
            credential: JSON.stringify(credential.toJSON()),
            nickname: nickname,
          }),
        });
      })
      .then(function (response) {
        if (response.status === 204) {
          window.location.reload();
          return;
        }
        return response.json().then(function (data) {
          throw new Error(data.error || "finish-failed");
        });
      })
      .catch(function (err) {
        button.disabled = false;
        showError(err && err.message);
      });
  });
})();
