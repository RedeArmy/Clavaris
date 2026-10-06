"use strict";

const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const SCRIPT_PATH = path.join(
  __dirname,
  "..",
  "..",
  "main",
  "resources",
  "static",
  "js",
  "i18n.js",
);

function load(lang) {
  global.document = { documentElement: { lang } };
  delete globalThis.clavarisI18n;
  delete require.cache[require.resolve(SCRIPT_PATH)];
  require(SCRIPT_PATH);
  return globalThis.clavarisI18n;
}

// The Spanish table is private to the script, so read it back out of the source to check it.
function spanishTable() {
  const source = fs.readFileSync(SCRIPT_PATH, "utf8");
  const body = /const SPANISH = (\{[\s\S]*?\n {2}\});/.exec(source)[1];
  return new Function("return " + body)();
}

test("keeps English text as it is", () => {
  const i18n = load("en");
  assert.equal(i18n.t("Are you sure?"), "Are you sure?");
  assert.equal(i18n.language(), "en");
});

test("translates to Spanish when the page is Spanish", () => {
  const i18n = load("es");
  assert.equal(i18n.t("Cancel"), "Cancelar");
  assert.equal(i18n.language(), "es");
});

test("reads the language from a regional tag", () => {
  const i18n = load("es-GT");
  assert.equal(i18n.language(), "es");
  assert.equal(i18n.t("Confirm"), "Confirmar");
});

test("falls back to English for unknown text, unknown languages and a missing lang", () => {
  assert.equal(load("es").t("Not in the table"), "Not in the table");
  assert.equal(load("fr").t("Cancel"), "Cancel");
  assert.equal(load("").t("Cancel"), "Cancel");
  global.document = {};
  delete require.cache[require.resolve(SCRIPT_PATH)];
  require(SCRIPT_PATH);
  assert.equal(globalThis.clavarisI18n.t("Cancel"), "Cancel");
});

test("does not mistake inherited object keys for translations", () => {
  assert.equal(load("es").t("constructor"), "constructor");
  assert.equal(load("es").t("toString"), "toString");
});

test("fills numbered placeholders in either language", () => {
  assert.equal(load("en").t("Remove {0}", "read:users"), "Remove read:users");
  assert.equal(load("es").t("Remove {0}", "read:users"), "Quitar read:users");
  assert.equal(load("es").t("Page {0} of {1}", 2, 5), "Página 2 de 5");
});

test("fills a value that itself contains a placeholder-like text once", () => {
  assert.equal(load("es").t("Remove {0}", "{1}"), "Quitar {1}");
});

test("formats numbers for the page's language", () => {
  assert.equal(load("en").number(1234.5, 1), "1,234.5");
  assert.equal(load("es").number(12345.5, 1), "12.345,5");
  // Spanish groups from five digits up, so four-digit numbers carry no separator.
  assert.equal(load("es").number(1234.5, 1), "1234,5");
  assert.equal(load("en").number(1234.56), "1,235");
});

test("formats file sizes in decimal units", () => {
  const en = load("en");
  assert.equal(en.size(512), "512 B");
  assert.equal(en.size(1500), "1.5 KB");
  assert.equal(en.size(10_000_000), "10 MB");
  assert.equal(en.size(2_500_000_000), "2.5 GB");
  assert.equal(load("es").size(1_500_000), "1,5 MB");
});

test("names countries in the page's language and falls back on bad input", () => {
  assert.equal(load("en").countryName("GT", "Guatemala"), "Guatemala");
  assert.equal(load("es").countryName("DE", "Germany"), "Alemania");
  assert.equal(load("es").countryName("not a code", "Fallback"), "Fallback");
});

test("every Spanish entry keeps the placeholders of its English key", () => {
  const placeholders = (text) => (text.match(/\{\d+}/g) || []).sort();
  const table = spanishTable();
  assert.ok(Object.keys(table).length > 30);
  for (const [english, spanish] of Object.entries(table)) {
    assert.deepEqual(placeholders(spanish), placeholders(english), english);
    assert.notEqual(spanish.trim(), "", english);
  }
});
