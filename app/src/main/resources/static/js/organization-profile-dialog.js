(() => {
  "use strict";

  // Translation helper: the shared i18n.js when the page loads it, plain English otherwise.
  const t = (text, ...args) =>
    globalThis.clavarisI18n?.t(text, ...args) ??
    args.reduce((out, arg, index) => out.split("{" + index + "}").join(String(arg)), text);

  // The "Edit organization" dialog on each card of "Your organizations". The form is an ordinary
  // multipart post that works without any of this; the script only makes the two fiddly fields
  // easier to get right:
  //   - the brand colour: a swatch (the browser's own picker) and a hex field that stay in step;
  //   - the logo: the chosen image previews in place before anything is sent, and a file that could
  //     never be accepted (wrong type, over 1 MB) is explained straight away instead of after the
  //     upload. The server checks all of it again; these limits mirror OrganizationLogo's.
  // Fully event-delegated, so it keeps working when htmx swaps the page's content (the pagination
  // links do) and there is nothing to re-initialise.
  const MAX_BYTES = 1024 * 1024;
  const ACCEPTED_TYPES = new Set(["image/png", "image/jpeg", "image/webp", "image/gif"]);
  const HEX_COLOUR = /^#[0-9a-f]{6}$/i;

  /** Why a chosen file cannot be used as the logo, or null when it can. */
  const logoProblem = (file) => {
    if (!file) {
      return null;
    }
    if (!ACCEPTED_TYPES.has(file.type)) {
      return t("The logo must be a PNG, JPEG, WebP or GIF image");
    }
    if (file.size > MAX_BYTES) {
      return t("The logo must not be larger than 1 MB");
    }
    return null;
  };

  // Per logo field: the object URL currently shown as a preview, so it can be released.
  const previews = new WeakMap();

  const showError = (field, message) => {
    const error = field.querySelector("[data-logo-error]");
    if (error) {
      error.textContent = message || "";
      error.hidden = !message;
    }
  };

  const setPreview = (field, url) => {
    const slot = field.querySelector(".clavaris-logo-field__preview");
    if (!slot) {
      return;
    }
    let image = slot.querySelector("[data-logo-preview]");
    if (!image) {
      image = document.createElement("img");
      image.setAttribute("data-logo-preview", "");
      image.alt = t("Chosen logo");
      slot.querySelector("[data-logo-placeholder]")?.remove();
      slot.append(image);
    }
    image.src = url;
  };

  const releasePreview = (field) => {
    const url = previews.get(field);
    if (url) {
      URL.revokeObjectURL(url);
      previews.delete(field);
    }
  };

  const onLogoChosen = (input) => {
    const field = input.closest("[data-logo-field]");
    if (!field) {
      return;
    }
    const file = input.files?.[0];
    const problem = logoProblem(file);
    showError(field, problem);
    if (problem) {
      input.value = "";
      return;
    }
    if (file) {
      releasePreview(field);
      const url = URL.createObjectURL(file);
      previews.set(field, url);
      setPreview(field, url);
      // Choosing a new logo and removing the current one are opposite requests.
      const remove = field.querySelector("[data-logo-remove]");
      if (remove) {
        remove.checked = false;
        field.querySelector(".clavaris-logo-field__preview")?.classList.remove(
          "clavaris-logo-field__preview--removing",
        );
      }
    }
  };

  const onRemoveToggled = (toggle) => {
    const field = toggle.closest("[data-logo-field]");
    if (!field) {
      return;
    }
    field
      .querySelector(".clavaris-logo-field__preview")
      ?.classList.toggle("clavaris-logo-field__preview--removing", toggle.checked);
    if (toggle.checked) {
      const input = field.querySelector("[data-logo-input]");
      if (input) {
        input.value = "";
      }
      showError(field, null);
    }
  };

  const onColourTyped = (text) => {
    const picker = text.closest("[data-color-field]")?.querySelector("[data-color-picker]");
    if (picker && HEX_COLOUR.test(text.value.trim())) {
      picker.value = text.value.trim().toLowerCase();
    }
  };

  const onColourPicked = (picker) => {
    const text = picker.closest("[data-color-field]")?.querySelector("[data-color-text]");
    if (text) {
      text.value = picker.value;
    }
  };

  document.addEventListener("change", (event) => {
    const target = event.target;
    if (target?.matches?.("[data-logo-input]")) {
      onLogoChosen(target);
    } else if (target?.matches?.("[data-logo-remove]")) {
      onRemoveToggled(target);
    }
  });

  document.addEventListener("input", (event) => {
    const target = event.target;
    if (target?.matches?.("[data-color-picker]")) {
      onColourPicked(target);
    } else if (target?.matches?.("[data-color-text]")) {
      onColourTyped(target);
    }
  });

  // Closing the dialog without saving drops what was chosen in it: the next time it opens it shows
  // the Organization as it is, not a half-finished edit.
  document.addEventListener(
    "close",
    (event) => {
      const form = event.target?.querySelector?.("[data-organization-profile-form]");
      if (form) {
        form.reset();
        form.querySelectorAll("[data-logo-field]").forEach((field) => {
          releasePreview(field);
          showError(field, null);
          field
            .querySelector(".clavaris-logo-field__preview")
            ?.classList.remove("clavaris-logo-field__preview--removing");
        });
      }
    },
    true,
  );

  // For the tests: the pure part, with no document to render.
  globalThis.clavarisOrganizationProfile = { logoProblem, MAX_BYTES };
})();
