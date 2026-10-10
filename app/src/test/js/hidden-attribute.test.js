"use strict";

const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const CSS = path.join(__dirname, "..", "..", "main", "resources", "static", "css");
const read = (name) => fs.readFileSync(path.join(CSS, name), "utf8");

// The hidden attribute has to hide an element whatever display value its classes set. Without a
// rule that says so, .clavaris-alert (display: block) and .clavaris-button (display: flex) beat the
// browser's built-in [hidden] { display: none }, and a notice or error box that is meant to appear
// only when a condition is met stays on screen all the time (the sign-in page's "Already signing in
// from another tab" notice and its empty passkey error box did exactly that).
test("consumer.css makes the hidden attribute win over a component's own display", () => {
  assert.match(read("consumer.css"), /(^|\n)\[hidden\]\s*\{[^}]*display:\s*none\s*!important/);
});

test("the components that set display are the reason the rule must be !important", () => {
  const css = read("clavaris.css");

  assert.match(css, /\.clavaris-alert\s*\{[^}]*display:\s*block/);
  assert.match(css, /\.clavaris-button\s*\{[^}]*display:\s*flex/);
});

// clavaris.css is shared with Clavaris's own dashboard and platform pages. The rule above is for the
// consuming application's pages only, so it must not creep into the shared file: doing so would
// change how a few platform screens (workspace roles, teams hierarchy) behave.
test("the shared stylesheet does not carry the consumer-only rule", () => {
  assert.doesNotMatch(read("clavaris.css"), /(^|\n)\[hidden\]\s*\{[^}]*display:\s*none\s*!important/);
});
