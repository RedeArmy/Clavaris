(() => {
  "use strict";

  // One-click copy for any <button data-copy-target="<id>">: copies the text content of the element
  // with that id (an identifier, URL or PEM block). Gives instant feedback on the button itself
  // ("Copied", checkmark icon) and announces it politely to assistive technology through the page's
  // own [data-copy-status] live region. Uses the async Clipboard API where available (secure
  // contexts: HTTPS and localhost) and falls back to a selection + execCommand("copy") when it is
  // missing or rejects, so a plain-HTTP pre-production host still works.

  const FEEDBACK_MS = 1800;

  const copyBySelection = (source) => {
    const range = document.createRange();
    range.selectNodeContents(source);
    const selection = window.getSelection();
    selection.removeAllRanges();
    selection.addRange(range);
    // NOSONAR: execCommand is deprecated, but it is the only copy route that works outside secure
    // contexts (a plain-HTTP pre-production host) and when the async Clipboard API rejects.
    const copied = document.execCommand("copy"); // NOSONAR
    selection.removeAllRanges();
    if (!copied) {
      throw new Error("copy command was rejected");
    }
  };

  // The async Clipboard API can reject even in a secure context (page not focused, permission
  // policy, an embedded webview), so a rejection falls back to the selection route too.
  const writeText = async (text, source) => {
    if (navigator.clipboard?.writeText) {
      try {
        await navigator.clipboard.writeText(text);
        return;
      } catch {
        // fall through to the selection-based copy below
      }
    }
    copyBySelection(source);
  };

  const showFeedback = (button, message) => {
    const label = button.querySelector("[data-copy-label]");
    const original = button.dataset.copyIdleLabel ?? label?.textContent ?? "";
    button.dataset.copyIdleLabel = original;
    button.dataset.copied = message === "Copied" ? "true" : "false";
    if (label) {
      label.textContent = message;
    }
    const status = document.querySelector("[data-copy-status]");
    if (status) {
      status.textContent = `${message}: ${button.getAttribute("aria-label") ?? ""}`.trim();
    }
    clearTimeout(button.copyTimer);
    button.copyTimer = setTimeout(() => {
      delete button.dataset.copied;
      if (label) {
        label.textContent = original;
      }
    }, FEEDBACK_MS);
  };

  document.addEventListener("click", (event) => {
    const button = event.target.closest("[data-copy-target]");
    if (!button) {
      return;
    }
    const source = document.getElementById(button.dataset.copyTarget);
    if (!source) {
      return;
    }
    writeText(source.textContent.trim(), source).then(
      () => showFeedback(button, "Copied"),
      () => showFeedback(button, "Copy failed"),
    );
  });
})();
