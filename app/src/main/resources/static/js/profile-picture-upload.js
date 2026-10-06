(() => {
  "use strict";

  // Translation helper: the shared i18n.js when the page loads it, plain English otherwise.
  const t = (text, ...args) =>
    globalThis.clavarisI18n?.t(text, ...args) ??
    args.reduce((out, arg, index) => out.split("{" + index + "}").join(String(arg)), text);

  // Live UX request, 2026-10-04: the profile picture's "Choose image" + "Upload" controls
  // (form[data-picture-upload], from the shared picture-card fragment: a user's profile and the
  // operator's own "Manage account"). Chooses first, uploads second, and tells the person what they
  // are about to upload before anything is sent:
  //   - "Upload" stays disabled until a usable file is chosen;
  //   - the chosen image previews in place of the current picture (URL.createObjectURL, revoked as
  //     soon as it is replaced), so a wrong pick is visible before it is saved;
  //   - the type and size are checked here with a plain message, rather than leaving the server to
  //     reject a 10 MB+ file after the whole upload (the server still validates, this only saves the
  //     round trip). Limits mirror ProfilePictureValidator (10 MB; JPEG, PNG, WebP, GIF).
  // Fully event-delegated, so it keeps working when htmx swaps the form in (the "Manage account"
  // dialog re-renders its content after every save); the disabled state is re-applied after each
  // swap. Without JavaScript the form is an ordinary file input plus submit button.
  const MAX_BYTES = 10 * 1024 * 1024;
  const ALLOWED_TYPES = new Set(["image/jpeg", "image/png", "image/webp", "image/gif"]);

  // Per form: the object URL currently shown as a preview, so it can be released.
  const previews = new WeakMap();

  const sizeLabel = (bytes) =>
    bytes >= 1024 * 1024
      ? (bytes / (1024 * 1024)).toFixed(1) + " MB"
      : Math.max(1, Math.round(bytes / 1024)) + " KB";

  const parts = (form) => ({
    input: form.querySelector('input[type="file"]'),
    submit: form.querySelector("[data-picture-submit]"),
    status: document.getElementById(form.dataset.pictureStatus || ""),
    preview: document.getElementById(form.dataset.picturePreview || ""),
  });

  const say = (status, message, isError) => {
    if (status) {
      status.textContent = message;
      status.classList.toggle("clavaris-field-error", isError);
      status.classList.toggle("clavaris-field-hint", !isError);
    }
  };

  const restore = (form, preview) => {
    const state = previews.get(form);
    if (state) {
      URL.revokeObjectURL(state.url);
      if (preview) {
        preview.setAttribute("src", state.original);
      }
      previews.delete(form);
    }
  };

  const show = (form, preview, file) => {
    restore(form, preview);
    if (preview) {
      previews.set(form, { url: URL.createObjectURL(file), original: preview.getAttribute("src") });
      preview.setAttribute("src", previews.get(form).url);
    }
  };

  const reject = (form, message) => {
    const { input, submit, status, preview } = parts(form);
    input.value = "";
    submit.disabled = true;
    restore(form, preview);
    say(status, message, true);
  };

  document.addEventListener("change", (event) => {
    const input = event.target;
    const form = input instanceof HTMLInputElement && input.type === "file" ? input.closest("form[data-picture-upload]") : null;
    if (!form) {
      return;
    }
    const { submit, status, preview } = parts(form);
    const file = input.files?.[0];
    if (!file) {
      submit.disabled = true;
      restore(form, preview);
      say(status, "", false);
    } else if (!ALLOWED_TYPES.has(file.type)) {
      reject(form, t("Choose a JPG, PNG, WebP or GIF image."));
    } else if (file.size > MAX_BYTES) {
      reject(form, t("That image is {0}. The limit is 10 MB.", sizeLabel(file.size)));
    } else {
      show(form, preview, file);
      submit.disabled = false;
      say(status, t("{0} ({1}) will replace the current picture.", file.name, sizeLabel(file.size)), false);
    }
  });

  // Nothing chosen yet means nothing to upload: applied on load and again after every htmx swap.
  const prime = () =>
    document.querySelectorAll("form[data-picture-upload]").forEach((form) => {
      const { input, submit } = parts(form);
      if (input && submit && !input.files?.length) {
        submit.disabled = true;
      }
    });

  document.addEventListener("htmx:afterSettle", prime);
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", prime);
  } else {
    prime();
  }
})();
