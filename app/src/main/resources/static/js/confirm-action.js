(() => {
  "use strict";

  // Click-through confirmation for any <form data-confirm="Message">. Destructive one-click
  // actions (rotate a secret, deactivate a key, revoke a session) used to fire immediately; now a
  // shared modal asks first. Optional attributes on the form:
  //   data-confirm-title   heading of the modal (default "Are you sure?")
  //   data-confirm-label   text of the confirm button (default "Confirm")
  //   data-confirm-tone    "danger" renders the confirm button in the destructive colour
  // Works for plain POSTs and HTMX forms alike: the submit event is held in the capture phase,
  // before HTMX's own listener on the form sees it, and replayed once the user agrees.

  let dialog;

  const build = () => {
    const element = document.createElement("dialog");
    element.className = "clavaris-dialog clavaris-dialog--confirm";
    element.setAttribute("aria-labelledby", "clavaris-confirm-title");
    element.setAttribute("aria-describedby", "clavaris-confirm-message");
    element.innerHTML =
      '<div class="clavaris-dialog__header"><div><h2 id="clavaris-confirm-title"></h2></div></div>' +
      '<div class="clavaris-dialog__body"><p id="clavaris-confirm-message"></p></div>' +
      '<div class="clavaris-dialog__actions">' +
      '<button type="button" class="clavaris-button clavaris-button--secondary clavaris-button--inline" data-confirm-cancel>Cancel</button>' +
      '<button type="button" class="clavaris-button clavaris-button--inline" data-confirm-accept></button>' +
      "</div>";
    document.body.appendChild(element);
    return element;
  };

  const ask = (form) => {
    dialog ??= build();
    dialog.querySelector("#clavaris-confirm-title").textContent = form.dataset.confirmTitle || "Are you sure?";
    dialog.querySelector("#clavaris-confirm-message").textContent = form.dataset.confirm;
    const accept = dialog.querySelector("[data-confirm-accept]");
    accept.textContent = form.dataset.confirmLabel || "Confirm";
    accept.classList.toggle("clavaris-button--danger", form.dataset.confirmTone === "danger");

    return new Promise((resolve) => {
      const finish = (confirmed) => {
        dialog.removeEventListener("click", onClick);
        dialog.removeEventListener("cancel", onCancel);
        dialog.close();
        resolve(confirmed);
      };
      const onClick = (event) => {
        if (event.target.closest("[data-confirm-accept]")) {
          finish(true);
        } else if (event.target.closest("[data-confirm-cancel]") || event.target === dialog) {
          finish(false);
        }
      };
      const onCancel = (event) => {
        event.preventDefault();
        finish(false);
      };
      dialog.addEventListener("click", onClick);
      dialog.addEventListener("cancel", onCancel);
      dialog.showModal();
      dialog.querySelector("[data-confirm-cancel]").focus();
    });
  };

  document.addEventListener(
    "submit",
    (event) => {
      const form = event.target;
      if (!form?.dataset?.confirm || form.dataset.confirmed === "true") {
        return;
      }
      event.preventDefault();
      event.stopImmediatePropagation();
      const submitter = event.submitter;
      void ask(form).then((confirmed) => {
        if (!confirmed) {
          return;
        }
        form.dataset.confirmed = "true";
        form.requestSubmit(submitter);
        delete form.dataset.confirmed;
      });
    },
    true,
  );
})();
