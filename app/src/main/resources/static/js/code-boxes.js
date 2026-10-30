(() => {
  "use strict";

  // Translation helper: the shared i18n.js when the page loads it, plain English otherwise.
  const t = (text, ...args) =>
    globalThis.clavarisI18n?.t(text, ...args) ??
    args.reduce((out, arg, index) => out.split("{" + index + "}").join(String(arg)), text);

  // A one-time code (ADR-0034) as separate boxes, one character each. It enhances a plain text
  // <input data-code-boxes="6">: the input stays in the form, keeps its name and is what is
  // submitted, so with no script the page still works with the single field. The boxes only fill it.
  //
  //   - typing moves to the next box; Backspace on an empty box goes back and clears the one before
  //   - arrows, Home and End move between boxes
  //   - a paste, or a phone filling the code in from the mail, is spread over the boxes whatever box
  //     it lands in; spaces and hyphens in it are dropped
  //   - when the last box is filled the form is sent, once, so entering the code is the whole step

  const ACCEPTED = /[A-Za-z0-9]/;

  const clean = (text) =>
    Array.from(text || "")
      .filter((character) => ACCEPTED.test(character))
      .join("")
      .toUpperCase();

  const build = (source) => {
    const length = Number.parseInt(source.dataset.codeBoxes, 10) || 6;
    const form = source.form;
    const group = document.createElement("div");
    group.className = "clavaris-code-boxes";
    group.setAttribute("role", "group");
    const label = source.id && document.querySelector('label[for="' + source.id + '"]');
    if (label) {
      label.id = label.id || source.id + "-label";
      group.setAttribute("aria-labelledby", label.id);
    }

    const boxes = Array.from({ length }, (_, index) => {
      const box = document.createElement("input");
      box.type = "text";
      box.className = "clavaris-code-box";
      box.inputMode = "text";
      box.autocomplete = index === 0 ? "one-time-code" : "off";
      box.autocapitalize = "characters";
      box.setAttribute("autocorrect", "off");
      box.spellcheck = false;
      box.setAttribute("aria-label", t("Character {0} of {1}", index + 1, length));
      group.appendChild(box);
      return box;
    });

    let sent = false;
    const sync = () => {
      source.value = boxes.map((box) => box.value).join("");
      if (!sent && source.value.length === length && form) {
        sent = true;
        form.requestSubmit();
      }
    };

    // Puts text into the boxes from `start` on, and leaves the cursor after the last one filled.
    const fill = (start, text) => {
      const characters = clean(text);
      let index = start;
      for (const character of characters) {
        if (index >= length) {
          break;
        }
        boxes[index].value = character;
        index += 1;
      }
      boxes[Math.min(index, length - 1)].focus();
      sync();
    };

    boxes.forEach((box, index) => {
      box.addEventListener("input", () => {
        const typed = clean(box.value);
        box.value = "";
        if (typed) {
          fill(index, typed);
        } else {
          sync();
        }
      });
      box.addEventListener("keydown", (event) => {
        const key = event.key;
        if (key === "Backspace" && !box.value && index > 0) {
          event.preventDefault();
          boxes[index - 1].value = "";
          boxes[index - 1].focus();
          sync();
        } else if (key === "ArrowLeft" && index > 0) {
          event.preventDefault();
          boxes[index - 1].focus();
        } else if (key === "ArrowRight" && index < length - 1) {
          event.preventDefault();
          boxes[index + 1].focus();
        } else if (key === "Home") {
          event.preventDefault();
          boxes[0].focus();
        } else if (key === "End") {
          event.preventDefault();
          boxes[length - 1].focus();
        }
      });
      box.addEventListener("paste", (event) => {
        event.preventDefault();
        fill(0, event.clipboardData?.getData("text") ?? "");
      });
      box.addEventListener("focus", () => box.select());
    });

    // The plain field is hidden from sight but stays in the form; clicking its label, or the browser
    // moving focus to it, lands on the first empty box instead.
    source.classList.add("clavaris-code-boxes__source");
    source.tabIndex = -1;
    source.addEventListener("focus", () => (boxes.find((box) => !box.value) ?? boxes[0]).focus());
    source.after(group);

    // Back to this page from the next one (the browser's back button): allow sending again.
    globalThis.addEventListener("pageshow", () => {
      sent = false;
    });

    boxes[0].focus();
  };

  document.querySelectorAll("input[data-code-boxes]").forEach(build);
})();
