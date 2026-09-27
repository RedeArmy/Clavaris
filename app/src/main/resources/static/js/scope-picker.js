(() => {
  "use strict";

  // Live UX request: the "Create a new Secret Key" scope picker's own category checkbox — same
  // parent/child shape event-type-picker.js already establishes for webhook event types, minus
  // its "select every category" toggle (redundant with a single category's own parent checkbox)
  // and its wildcard concept (no "all scopes" sentinel exists the way "*" does for a webhook
  // event type). Same CSP posture as that script and organization-dialog.js: no inline
  // onchange="..." attributes, one delegated listener on document, safe to load unconditionally
  // on every dashboard page (a no-op wherever #scope-options doesn't exist). Shared
  // category-checkbox-picker.js logic (loaded before this script) covers the parts identical to
  // event-type-picker.js's own.

  const CONTAINER_ID = "scope-options";
  const CATEGORY_CHECKBOX_CLASS = "clavaris-scope-category-checkbox";

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

  document.addEventListener("change", (event) => {
    if (event.target.matches?.("." + CATEGORY_CHECKBOX_CLASS)) {
      toggleScopeCategory(event.target);
      return;
    }
    if (event.target.matches?.("#scope-options .clavaris-event-option input[type=checkbox]")) {
      window.ClavarisCategoryPicker.updateCategoryCheckboxState(
        CONTAINER_ID,
        CATEGORY_CHECKBOX_CLASS,
        event.target,
      );
    }
  });

  // Initial load: derive every category checkbox's own starting state from whichever scope
  // checkboxes already came pre-checked — not reachable on the create form today (it always
  // starts empty), but keeps this script correct the day an edit form reuses the same picker.
  document.addEventListener("DOMContentLoaded", () => {
    window.ClavarisCategoryPicker.initializeCategoryCheckboxes(CONTAINER_ID, CATEGORY_CHECKBOX_CLASS);
  });
})();
