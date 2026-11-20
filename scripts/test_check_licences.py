#!/usr/bin/env python3
"""Tests for check-licences.py (TD-FUT-082). Run: python3 scripts/test_check_licences.py"""
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("check_licences", HERE / "check-licences.py")
cl = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cl)

POLICY = json.loads((HERE.parent / "infra/licences/licence-policy.json").read_text())


def lib(name, *licences, purl=None, version="1.0"):
    entries = [{"license": {"id": x}} if " " not in x and "-" in x or x.isalnum() else {"license": {"name": x}}
               for x in licences]
    return {"type": "library", "name": name, "version": version,
            "purl": purl or f"pkg:maven/org.x/{name}@{version}", "licenses": entries}


def deb(name, *licences):
    return {"type": "library", "name": name, "version": "1", "purl": f"pkg:deb/ubuntu/{name}@1",
            "licenses": [{"license": {"name": x}} for x in licences]}


def violations(*components, policy=POLICY):
    return [c["name"] for c, *_ in cl.evaluate({"components": list(components)}, policy)[0]]


class Classification(unittest.TestCase):
    def test_common_licences(self):
        for text, expected in [
            ("Apache-2.0", "permissive"), ("The Apache Software License, Version 2.0", "permissive"),
            ("MIT", "permissive"), ("BSD-3-Clause", "permissive"), ("PostgreSQL", "permissive"),
            ("EPL-2.0", "weak-copyleft"), ("Eclipse Public License v2.0", "weak-copyleft"),
            ("LGPL-2.1-or-later", "weak-copyleft"), ("MPL-2.0", "weak-copyleft"),
            ("GPL-2.0-with-classpath-exception", "weak-copyleft"),
            ("GPL-3.0-only", "strong-copyleft"), ("GNU General Public License", "strong-copyleft"),
            ("AGPL-3.0-only", "network-copyleft"), ("SSPL-1.0", "source-available"),
            ("RSALv2", "source-available"), ("BUSL-1.1", "source-available"),
            ("CC-BY-NC-4.0", "non-commercial"), ("something nobody has heard of", "unknown"),
        ]:
            with self.subTest(text):
                self.assertEqual(cl.classify_expression(text), expected)

    def test_or_takes_the_lesser_risk_and_and_the_greater(self):
        self.assertEqual(cl.classify_expression("GPL-2.0-only OR Apache-2.0"), "permissive")
        self.assertEqual(cl.classify_expression("(MIT AND GPL-3.0-only)"), "strong-copyleft")
        self.assertEqual(cl.classify_expression("RSALv2 OR SSPL-1.0 OR AGPL-3.0-only"), "network-copyleft")

    def test_a_dual_licence_listed_as_two_entries_takes_the_lesser_risk(self):
        self.assertEqual(cl.component_class(["EPL-1.0", "LGPL-2.1-only"]), "weak-copyleft")
        self.assertEqual(cl.component_class(["GPL-2.0-only", "MIT"]), "permissive")

    def test_no_licence_is_unknown(self):
        self.assertEqual(cl.component_class([]), "unknown")


class Policy(unittest.TestCase):
    def test_permissive_and_weak_copyleft_libraries_pass(self):
        self.assertEqual(violations(lib("a", "Apache-2.0"), lib("b", "EPL-2.0"), lib("c", "LGPL-2.1-only")), [])

    def test_strong_copyleft_library_fails_but_the_same_os_package_passes(self):
        self.assertEqual(violations(lib("a", "GPL-3.0-only")), ["a"])
        self.assertEqual(violations(deb("bash", "GPL-3.0-or-later")), [])

    def test_network_copyleft_and_source_available_fail_everywhere(self):
        self.assertEqual(violations(lib("a", "AGPL-3.0-only"), deb("redis", "SSPL-1.0")), ["a", "redis"])

    def test_a_library_with_no_licence_fails_and_an_os_package_with_none_does_not(self):
        self.assertEqual(violations({"type": "library", "name": "mystery", "purl": "pkg:maven/x/mystery@1"}), ["mystery"])
        self.assertEqual(violations({"type": "library", "name": "pkg", "purl": "pkg:deb/ubuntu/pkg@1"}), [])

    def test_a_reviewed_exception_lets_it_through(self):
        policy = {**POLICY, "exceptions": [{"name": "a", "reason": "dual licensed, we take MIT", "reviewedOn": "2026-11-20"}]}
        self.assertEqual(violations(lib("a", "GPL-3.0-only"), policy=policy), [])

    def test_an_exception_without_a_reason_is_refused(self):
        policy = {**POLICY, "exceptions": [{"name": "a", "reviewedOn": "2026-11-20"}]}
        with self.assertRaises(SystemExit):
            violations(lib("a", "GPL-3.0-only"), policy=policy)

    def test_an_exception_for_one_version_does_not_cover_another(self):
        policy = {**POLICY, "exceptions": [{"name": "a", "version": "1.0", "reason": "r", "reviewedOn": "2026-11-20"}]}
        self.assertEqual(violations(lib("a", "GPL-3.0-only", version="1.0"), policy=policy), [])
        self.assertEqual(violations(lib("a", "GPL-3.0-only", version="2.0"), policy=policy), ["a"])


class Cli(unittest.TestCase):
    def sbom(self, tmp, *components):
        path = Path(tmp) / "sbom.json"
        path.write_text(json.dumps({"components": list(components)}))
        return str(path)

    def test_check_exit_codes(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(cl.main(["x", "check", self.sbom(tmp, lib("a", "MIT"))]), 0)
            self.assertEqual(cl.main(["x", "check", self.sbom(tmp, lib("a", "AGPL-3.0-only"))]), 1)

    def test_covered_flags_a_library_missing_from_the_notices(self):
        with tempfile.TemporaryDirectory() as tmp:
            sbom = self.sbom(tmp, lib("a", "MIT"), lib("b", "MIT"))
            notices = Path(tmp) / "n.md"
            notices.write_text("| a | 1.0 | MIT | permissive |\n")
            self.assertEqual(cl.main(["x", "covered", sbom, str(notices)]), 1)
            notices.write_text("| a | 1.0 | MIT | permissive |\n| b | 1.0 | MIT | permissive |\n")
            self.assertEqual(cl.main(["x", "covered", sbom, str(notices)]), 0)


if __name__ == "__main__":
    unittest.main()
