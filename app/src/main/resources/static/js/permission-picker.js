(() => {
  "use strict";

  // Turns a Workspace Role's Permissions textarea into chips. The textarea stays the single source
  // of truth for the form (one opaque permission per line); this script only edits it through a
  // friendlier interface and writes every change back, so the server contract is untouched and the
  // page still works with JavaScript off. Markup contract (see permissions-field.html):
  //   [data-permission-picker]            wrapper
  //   [data-permission-source]            the textarea (readonly for the reserved role)
  //   [data-permission-suggestion]        toggle buttons carrying data-permission="<value>"

  // Splits free text (typed, pasted, or the textarea's own value) into distinct permission strings:
  // separated by newlines or commas, each trimmed, order preserved, blanks dropped. Spaces INSIDE a
  // string are kept — a permission is opaque, so existing data containing one must round-trip
  // through this picker unchanged.
  const tokenize = (raw) => {
    const seen = new Set();
    for (const part of String(raw ?? "").split(/[\r\n,]+/)) {
      const token = part.trim();
      if (token) {
        seen.add(token);
      }
    }
    return [...seen];
  };

  const serialize = (permissions) => [...permissions].join("\n");

  const el = (tag, className, text) => {
    const node = document.createElement(tag);
    if (className) {
      node.className = className;
    }
    if (text !== undefined) {
      node.textContent = text;
    }
    return node;
  };

  const init = (root) => {
    const source = root.querySelector("[data-permission-source]");
    if (!source) {
      return;
    }
    const locked = source.readOnly;
    const suggestions = [...root.querySelectorAll("[data-permission-suggestion]")];
    let permissions = tokenize(source.value);

    const ui = el("div", "clavaris-permission-picker__ui");
    const chips = el("ul", "clavaris-permission-picker__selected");
    chips.setAttribute("aria-label", "Selected permissions");
    ui.append(chips);

    let input;
    if (!locked) {
      input = el("input", "clavaris-permission-picker__input");
      input.type = "text";
      input.autocomplete = "off";
      input.spellcheck = false;
      input.setAttribute("aria-label", "Add a permission");
      input.placeholder = "Type a permission and press Enter";
      ui.append(input);
    }
    source.after(ui);

    const sync = () => {
      source.value = serialize(permissions);
      chips.replaceChildren();
      if (permissions.length === 0) {
        chips.append(el("li", "clavaris-permission-picker__empty", "No permissions assigned."));
      }
      for (const permission of permissions) {
        const chip = el("li", "clavaris-chip");
        chip.append(el("span", "clavaris-chip__label", permission));
        if (!locked) {
          const remove = el("button", "clavaris-chip__remove");
          remove.type = "button";
          remove.dataset.permissionRemove = permission;
          remove.setAttribute("aria-label", `Remove ${permission}`);
          remove.textContent = "×";
          chip.append(remove);
        }
        chips.append(chip);
      }
      for (const button of suggestions) {
        button.setAttribute("aria-pressed", String(permissions.includes(button.dataset.permission)));
      }
    };

    const add = (raw) => {
      permissions = tokenize([...permissions, ...tokenize(raw)].join("\n"));
      sync();
    };

    const remove = (permission) => {
      permissions = permissions.filter((existing) => existing !== permission);
      sync();
    };

    root.addEventListener("click", (event) => {
      const removeButton = event.target.closest("[data-permission-remove]");
      if (removeButton) {
        remove(removeButton.dataset.permissionRemove);
        input?.focus();
        return;
      }
      const suggestion = event.target.closest("[data-permission-suggestion]");
      if (suggestion && !locked) {
        const value = suggestion.dataset.permission;
        if (permissions.includes(value)) {
          remove(value);
        } else {
          add(value);
        }
      }
    });

    if (input) {
      input.addEventListener("keydown", (event) => {
        if (event.key === "Enter" || event.key === ",") {
          // Enter must add the chip, never submit the surrounding form.
          event.preventDefault();
          add(input.value);
          input.value = "";
        } else if (event.key === "Backspace" && input.value === "" && permissions.length > 0) {
          remove(permissions.at(-1));
        }
      });
      input.addEventListener("paste", (event) => {
        const text = event.clipboardData?.getData("text");
        if (text && /[\r\n,]/.test(text.trim())) {
          event.preventDefault();
          add(text);
        }
      });
      // A permission typed but not confirmed is still kept when focus leaves the field.
      input.addEventListener("blur", () => {
        if (input.value.trim()) {
          add(input.value);
          input.value = "";
        }
      });
    }

    root.classList.add("is-enhanced");
    sync();
  };

  if (typeof document !== "undefined" && document.querySelectorAll) {
    for (const root of document.querySelectorAll("[data-permission-picker]")) {
      init(root);
    }
  }

  if (typeof module !== "undefined") {
    module.exports = { tokenize, serialize };
  }
})();
