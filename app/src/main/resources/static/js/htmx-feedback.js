(() => {
  "use strict";

  // Translation helper: the shared i18n.js when the page loads it, plain English otherwise.
  const t = (text, ...args) =>
    globalThis.clavarisI18n?.t(text, ...args) ??
    args.reduce((out, arg, index) => out.split("{" + index + "}").join(String(arg)), text);

  // Global feedback for failed HTMX requests. Every mutation and paginated list on the dashboard is
  // an hx-post/hx-get; when one fails HTMX swaps nothing and says nothing, so the page looks as if
  // the click did nothing (or worked). This listens once, on <body>, and tells the user what happened
  // in a toast that screen readers announce (role="alert"). Two cases get more than a message:
  //  - an expired session: the server redirects the request to the login page, HTMX follows it and
  //    would swap an entire login page into a small fragment target. The swap is cancelled and the
  //    page reloaded instead, which sends the user through the normal sign-in-and-return flow.
  //  - a request that never reached the server (offline, timeout).

  const LOGIN_PATH = "/platform/login";
  const DISMISS_AFTER_MS = 10000;
  const MAX_TOASTS = 3;

  const MESSAGES = new Map([
    [0, "Couldn't reach the server. Check your connection and try again."],
    [400, "That request was rejected. Check what you entered and try again."],
    [401, "Your session has expired. Reload the page and sign in again."],
    [403, "You don't have permission to do that."],
    [404, "That item no longer exists. Reload the page to see the latest."],
    [409, "That conflicts with a recent change. Reload the page and try again."],
    [413, "That is too large to send."],
    [429, "Too many requests. Wait a moment and try again."],
  ]);
  const SERVER_ERROR = "Something went wrong on our side. Try again in a moment.";
  const FALLBACK = "That didn't work. Try again.";
  const TIMEOUT = "The request took too long. Try again.";

  const messageFor = (status) => {
    if (MESSAGES.has(status)) {
      return t(MESSAGES.get(status));
    }
    return t(status >= 500 ? SERVER_ERROR : FALLBACK);
  };

  // True when a response ended up on the login page, i.e. the session is gone.
  const isLoginRedirect = (responseUrl) => {
    if (!responseUrl) {
      return false;
    }
    try {
      return new URL(responseUrl, "http://localhost").pathname.endsWith(LOGIN_PATH);
    } catch {
      return false;
    }
  };

  let region;

  const ensureRegion = () => {
    if (!region) {
      region = document.createElement("div");
      region.className = "clavaris-toast-region";
      region.setAttribute("popover", "manual");
      region.setAttribute("aria-label", t("Notifications"));
      document.body.appendChild(region);
    }
    return region;
  };

  // A manual popover lives in the browser's top layer, so a toast raised from inside an open modal
  // dialog still shows above it; re-showing it moves it back to the top of that layer.
  const showRegion = () => {
    if (typeof region.showPopover !== "function") {
      return;
    }
    try {
      region.hidePopover();
    } catch {
      // not currently open
    }
    region.showPopover();
  };

  const hideRegion = () => {
    if (typeof region.hidePopover === "function") {
      try {
        region.hidePopover();
      } catch {
        // already closed
      }
    }
  };

  const dismiss = (toast) => {
    clearTimeout(toast.dismissTimer);
    toast.remove();
    if (region.children.length === 0) {
      hideRegion();
    }
  };

  const showToast = (message) => {
    ensureRegion();
    const existing = [...region.children].find((toast) => toast.dataset.message === message);
    if (existing) {
      dismiss(existing);
    }
    while (region.children.length >= MAX_TOASTS) {
      dismiss(region.firstElementChild);
    }

    const toast = document.createElement("div");
    toast.className = "clavaris-toast";
    toast.setAttribute("role", "alert");
    toast.dataset.message = message;

    const text = document.createElement("span");
    text.className = "clavaris-toast__message";
    text.textContent = message;

    const close = document.createElement("button");
    close.type = "button";
    close.className = "clavaris-toast__close";
    close.setAttribute("aria-label", t("Dismiss notification"));
    close.textContent = "×";
    close.addEventListener("click", () => dismiss(toast));

    toast.append(text, close);
    region.append(toast);
    showRegion();
    toast.dismissTimer = setTimeout(() => dismiss(toast), DISMISS_AFTER_MS);
  };

  const root = document.body;

  root.addEventListener("htmx:beforeSwap", (event) => {
    if (isLoginRedirect(event.detail?.xhr?.responseURL)) {
      event.detail.shouldSwap = false;
      window.location.reload();
    }
  });

  root.addEventListener("htmx:responseError", (event) => {
    showToast(messageFor(event.detail?.xhr?.status ?? 0));
  });

  root.addEventListener("htmx:sendError", () => showToast(messageFor(0)));

  root.addEventListener("htmx:timeout", () => showToast(t(TIMEOUT)));

  if (typeof module !== "undefined") {
    module.exports = { messageFor, isLoginRedirect };
  }
})();
