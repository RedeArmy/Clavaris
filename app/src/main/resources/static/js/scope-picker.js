(() => {
  "use strict";

  // Live UX request: the "Create a new Secret Key" scope picker's own category checkbox — same
  // parent/child shape event-type-picker.js already establishes for webhook event types, minus
  // its "select every category" toggle (redundant with a single category's own parent checkbox)
  // and its wildcard concept (no "all scopes" sentinel exists the way "*" does for a webhook
  // event type). Same CSP posture as that script and organization-dialog.js: no inline
  // onchange="..." attributes, one delegated listener on document, safe to load unconditionally
  // on every dashboard page (a no-op wherever #scope-options doesn't exist).

  const toggleScopeCategory = (categoryCheckbox) => {
    const category = categoryCheckbox.dataset.category;
    const checked = categoryCheckbox.checked;
    categoryCheckbox.indeterminate = false;
    document
      .querySelectorAll(
        '#scope-options .clavaris-event-option input[data-category="' + category + '"]',
      )
      .forEach((checkbox) => {
        checkbox.checked = checked;
      });
  };

  const updateScopeCategoryCheckboxState = (changedCheckbox) => {
    const category = changedCheckbox.dataset.category;
    const categoryCheckbox = document.querySelector(
      '#scope-options .clavaris-scope-category-checkbox[data-category="' + category + '"]',
    );
    if (!categoryCheckbox) {
      return;
    }
    const children = document.querySelectorAll(
      '#scope-options .clavaris-event-option input[data-category="' + category + '"]',
    );
    let checkedCount = 0;
    children.forEach((checkbox) => {
      if (checkbox.checked) {
        checkedCount += 1;
      }
    });
    categoryCheckbox.checked = checkedCount === children.length;
    categoryCheckbox.indeterminate = checkedCount > 0 && checkedCount < children.length;
  };

  document.addEventListener("change", (event) => {
    if (event.target.matches?.(".clavaris-scope-category-checkbox")) {
      toggleScopeCategory(event.target);
      return;
    }
    if (event.target.matches?.("#scope-options .clavaris-event-option input[type=checkbox]")) {
      updateScopeCategoryCheckboxState(event.target);
    }
  });

  // Initial load: derive every category checkbox's own starting state from whichever scope
  // checkboxes already came pre-checked — not reachable on the create form today (it always
  // starts empty), but keeps this script correct the day an edit form reuses the same picker.
  document.addEventListener("DOMContentLoaded", () => {
    document
      .querySelectorAll("#scope-options .clavaris-scope-category-checkbox")
      .forEach((categoryCheckbox) => {
        const firstChild = document.querySelector(
          '#scope-options .clavaris-event-option input[data-category="' +
            categoryCheckbox.dataset.category +
            '"]',
        );
        if (firstChild) {
          updateScopeCategoryCheckboxState(firstChild);
        }
      });
  });
})();
