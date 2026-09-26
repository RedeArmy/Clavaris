(() => {
  "use strict";

  // Live UX request, 2026-09-26 (real bug found, live): the webhook event-type picker's own
  // parent/child category checkboxes and "All Events" toggle were originally wired via inline
  // onchange="..." attributes plus an inline <script> block inside event-type-picker.html — both
  // silently blocked by ContentSecurityPolicyHeaderWriter's own DASHBOARD_PAGE_POLICY
  // (script-src 'self', deliberately never 'unsafe-inline' — see that class's own ADR-0025
  // Javadoc). MockMvc content-assertion tests render the markup but never execute JS or enforce
  // CSP, so this passed every automated check while doing nothing in a real browser. Same
  // event-delegation shape organization-dialog.js already establishes: one listener on document,
  // safe to load unconditionally on every dashboard page (a no-op wherever #event-type-options
  // doesn't exist).

  const toggleEventCategory = (categoryCheckbox) => {
    const category = categoryCheckbox.dataset.category;
    const checked = categoryCheckbox.checked;
    categoryCheckbox.indeterminate = false;
    document
      .querySelectorAll(
        '#event-type-options .clavaris-event-option input[data-category="' + category + '"]',
      )
      .forEach((checkbox) => {
        if (!checkbox.disabled) {
          checkbox.checked = checked;
        }
      });
  };

  const updateEventCategoryCheckboxState = (changedCheckbox) => {
    const category = changedCheckbox.dataset.category;
    const categoryCheckbox = document.querySelector(
      '#event-type-options .clavaris-event-category-checkbox[data-category="' + category + '"]',
    );
    if (!categoryCheckbox) {
      return;
    }
    const children = document.querySelectorAll(
      '#event-type-options .clavaris-event-option input[data-category="' + category + '"]',
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

  // Turning the wildcard on checks every box (category and individual alike) so the toggle's own
  // visible state matches "everything is selected" — turning it back off clears every box.
  const toggleAllEventsWildcard = (checked) => {
    const hiddenValue = document.getElementById("all-events-hidden-value");
    if (hiddenValue) {
      hiddenValue.disabled = !checked;
    }
    document.querySelectorAll("#event-type-options input[type=checkbox]").forEach((checkbox) => {
      checkbox.disabled = checked;
      checkbox.indeterminate = false;
      checkbox.checked = checked;
    });
  };

  document.addEventListener("change", (event) => {
    if (event.target.id === "all-events-checkbox") {
      toggleAllEventsWildcard(event.target.checked);
      return;
    }
    if (event.target.matches?.(".clavaris-event-category-checkbox")) {
      toggleEventCategory(event.target);
      return;
    }
    if (event.target.matches?.("#event-type-options .clavaris-event-option input[type=checkbox]")) {
      updateEventCategoryCheckboxState(event.target);
    }
  });

  // Initial load: derive every category checkbox's own starting state from whichever event
  // checkboxes already came pre-checked (editing an existing endpoint), and — same as a live
  // toggle — an endpoint already subscribed to "*" renders with every checkbox already checked
  // and disabled.
  document.addEventListener("DOMContentLoaded", () => {
    document
      .querySelectorAll("#event-type-options .clavaris-event-category-checkbox")
      .forEach((categoryCheckbox) => {
        const firstChild = document.querySelector(
          '#event-type-options .clavaris-event-option input[data-category="' +
            categoryCheckbox.dataset.category +
            '"]',
        );
        if (firstChild) {
          updateEventCategoryCheckboxState(firstChild);
        }
      });
    const allEventsCheckbox = document.getElementById("all-events-checkbox");
    if (allEventsCheckbox?.checked) {
      toggleAllEventsWildcard(true);
    }
  });
})();
