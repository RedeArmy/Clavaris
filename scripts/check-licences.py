#!/usr/bin/env python3
"""TD-FUT-082: applies infra/licences/licence-policy.json to a CycloneDX SBOM.

Usage:
  check-licences.py check   <sbom.cdx.json> [policy.json]   exit 1 if a component breaks the policy
  check-licences.py notices <sbom.cdx.json> [policy.json]   prints THIRD-PARTY-NOTICES.md to stdout
  check-licences.py covered <sbom.cdx.json> <notices.md>    exit 1 if a Java library is not in the notices

The SBOM is the one CI already generates from the built image (anchore/sbom-action), so what is
checked is what ships. Standard library only: it runs on a bare CI runner.

A component's licence text is classified (permissive, weak-copyleft, strong-copyleft, network-copyleft,
source-available, non-commercial, unknown). An SPDX "A OR B" lets us choose, so it takes the lesser
risk; "A AND B" takes the greater. A component with no licence at all is `unknown`, which fails for
Java libraries: nothing may ship that nobody has read the terms of.
"""
import json
import re
import sys
from pathlib import Path

RANK = ["permissive", "weak-copyleft", "strong-copyleft", "network-copyleft",
        "source-available", "non-commercial", "unknown"]

# Order matters: the first rule that matches wins, so the more specific terms come first.
RULES = [
    (r"\bagpl|affero", "network-copyleft"),
    (r"\bsspl|server side public", "source-available"),
    (r"\brsal|redis source available|\bbusl|business source|commons clause|elastic license|\bpolyform|\bfsl-", "source-available"),
    (r"\bcc-by-nc|non-?commercial", "non-commercial"),
    # A GPL with the classpath exception is what the JDK and Jakarta use: linking is allowed.
    (r"classpath[- ]exception", "weak-copyleft"),
    (r"\blgpl|lesser general public|library general public", "weak-copyleft"),
    (r"\bgpl|general public license", "strong-copyleft"),
    (r"\bmpl|mozilla public|\bepl|eclipse public|\bcddl|common development and distribution|\bcpl\b|common public|\beupl|\bosl-", "weak-copyleft"),
    (r"apache|\bmit\b|\bbsd|\bisc\b|zlib|0bsd|unlicense|\bcc0|public domain|postgresql|bluoak|blueoak|python-2|\bpsf|\bwtfpl|\bhpnd|curl|openssl|boost|\bx11|\bjson\b|ofl-|\bcc-by-4|\bcc-by-3|icu|unicode|w3c|bouncy castle|the go license|\bgo license|\bncsa|\bafl|\bartistic|\bopenldap|\bvim|\bbzip2|\blibpng|\blibtiff|\bgd\b|\bmit-0|\bcc-by-sa-4", "permissive"),
]


def classify_one(text: str) -> str:
    lowered = text.lower()
    for pattern, klass in RULES:
        if re.search(pattern, lowered):
            return klass
    return "unknown"


def classify_expression(text: str) -> str:
    """OR picks the lesser risk, AND the greater; a parenthesis is only grouping, so it is ignored."""
    text = text.replace("(", " ").replace(")", " ")
    if re.search(r"\sOR\s", text):
        return min((classify_expression(part) for part in re.split(r"\sOR\s", text)), key=RANK.index)
    if re.search(r"\sAND\s", text):
        return max((classify_expression(part) for part in re.split(r"\sAND\s", text)), key=RANK.index)
    return classify_one(text.strip())


def licence_texts(component: dict) -> list:
    texts = []
    for entry in component.get("licenses", []) or []:
        if "expression" in entry:
            texts.append(entry["expression"])
        else:
            lic = entry.get("license", {})
            texts.append(lic.get("id") or lic.get("name") or "")
    return [t for t in texts if t.strip()]


def texts_are_usable(texts: list) -> bool:
    """A declared licence the classifier understands wins over an override: the override is only for
    what the artefact's metadata omits or garbles (a URL, a file hash), never to overrule it."""
    return bool(texts) and component_class(texts) != "unknown"


def component_class(texts: list) -> str:
    if not texts:
        return "unknown"
    # Several entries are alternatives the author lists side by side (a dual licence), so the
    # lesser risk applies, the same as an explicit OR.
    return min((classify_expression(t) for t in texts), key=RANK.index)


def kind(component: dict) -> str:
    purl = component.get("purl", "") or ""
    if purl.startswith("pkg:maven/"):
        return "library"
    if purl.startswith(("pkg:deb/", "pkg:apk/", "pkg:rpm/")):
        return "os"
    # Anything else (a jar syft found without Maven coordinates, a binary) is held to the strict
    # standard: it is bundled code, not an operating-system package.
    return "library" if component.get("type") == "library" else "os"


def load(path: str) -> dict:
    return json.loads(Path(path).read_text(encoding="utf-8"))


def is_first_party(policy: dict, component: dict) -> bool:
    prefix = policy.get("firstParty", {}).get("purlPrefix")
    return bool(prefix) and (component.get("purl") or "").startswith(prefix)


def override_for(policy: dict, component: dict):
    """The licence a person read for an artefact whose own metadata omits or garbles it."""
    for item in policy.get("licenceOverrides", []):
        for field in ("name", "licence", "source", "reviewedOn"):
            if not item.get(field):
                raise SystemExit(f"licence-policy.json: a licence override needs name, licence, source and reviewedOn: {item}")
        if item["name"] == component.get("name") and item.get("version") in (None, component.get("version")):
            return item
    return None


def exception_for(policy: dict, component: dict):
    for item in policy.get("exceptions", []):
        for field in ("name", "reason", "reviewedOn"):
            if not item.get(field):
                raise SystemExit(f"licence-policy.json: an exception needs name, reason and reviewedOn: {item}")
        if item["name"] == component.get("name") and item.get("version") in (None, component.get("version")):
            return item
    return None


def evaluate(sbom: dict, policy: dict):
    violations, rows = [], []
    for component in sbom.get("components", []):
        if component.get("type") not in ("library", "application", "framework"):
            continue
        if is_first_party(policy, component):
            continue
        texts = licence_texts(component)
        override = override_for(policy, component)
        if override and not texts_are_usable(texts):
            texts = [override["licence"]]
        klass = component_class(texts)
        k = kind(component)
        rows.append((component, k, klass, texts))
        if klass in policy["failOnClass"][k] and not exception_for(policy, component):
            violations.append((component, k, klass, texts))
    return violations, rows


def cmd_check(sbom_path: str, policy_path: str) -> int:
    sbom, policy = load(sbom_path), load(policy_path)
    violations, rows = evaluate(sbom, policy)
    libraries = sum(1 for _, k, _, _ in rows if k == "library")
    print(f"{len(rows)} components checked ({libraries} Java libraries or bundled code).")
    counts = {}
    for _, k, klass, _ in rows:
        counts[(k, klass)] = counts.get((k, klass), 0) + 1
    for (k, klass), n in sorted(counts.items()):
        print(f"  {k:8} {klass:18} {n}")
    if not violations:
        print("OK: no component breaks the licence policy.")
        return 0
    print(f"\nFAIL: {len(violations)} component(s) break the licence policy:", file=sys.stderr)
    for component, k, klass, texts in violations:
        shown = "; ".join(texts) or "no licence declared"
        print(f"  {component.get('name')} {component.get('version', '')} [{k}] is {klass}: {shown}", file=sys.stderr)
    print("Replace it, or record a reviewed exception (name, reason, reviewedOn) in "
          "infra/licences/licence-policy.json.", file=sys.stderr)
    return 1


def library_rows(rows):
    seen = {}
    for component, k, klass, texts in rows:
        if k != "library":
            continue
        # One line per library name: the same artefact appears once per jar that contains it.
        seen.setdefault(component.get("name"), (component, klass, texts))
    return [seen[name] for name in sorted(seen, key=str.lower)]


def cmd_notices(sbom_path: str, policy_path: str) -> int:
    sbom, policy = load(sbom_path), load(policy_path)
    _, rows = evaluate(sbom, policy)
    out = [
        "# Third-party notices",
        "",
        "Clavaris itself is proprietary (see `LICENSE`). This file lists the third-party code that ships",
        "in its container image, with the licence each component declares, and the services that run",
        "beside it. It is generated from the image's CycloneDX SBOM by `scripts/check-licences.py notices`",
        "and checked in CI (`scripts/check-licences.py covered`) so a new dependency cannot ship without",
        "appearing here; versions are as of the last regeneration. Where an artefact's own metadata omits",
        "its licence, the one read from its pom is recorded in `infra/licences/licence-policy.json`, with",
        "where it was read. Licence terms to quote to a customer",
        "are in `docs/03-architecture/adr/0050-licence-review-and-redis-versus-valkey.md`.",
        "",
        "## Java libraries and bundled code",
        "",
        "| Component | Version | Licence | Class |",
        "|---|---|---|---|",
    ]
    for component, klass, texts in library_rows(rows):
        declared = "; ".join(texts).replace("|", "\\|") or "none declared"
        out.append(f"| {component.get('name')} | {component.get('version', '')} | {declared} | {klass} |")
    out += [
        "",
        "Operating-system packages of the base image (Ubuntu) carry their own licences, listed in the",
        "image's SBOM; they are separate programs the image aggregates, not code Clavaris links against.",
        "",
        "## Services that run beside Clavaris",
        "",
        "| Service | Image | Licence |",
        "|---|---|---|",
        "| PostgreSQL | `postgres` | PostgreSQL License (permissive) |",
        "| Redis | `redis:8` | RSALv2, SSPLv1 or AGPLv3 at the user's choice (Redis 8); see ADR-0050 |",
        "| Caddy | `caddy` | Apache-2.0 |",
        "",
    ]
    sys.stdout.write("\n".join(out))
    return 0


def cmd_covered(sbom_path: str, notices_path: str) -> int:
    sbom = load(sbom_path)
    notices = Path(notices_path).read_text(encoding="utf-8")
    # The default policy for what is Clavaris's own code and what is overridden, but nothing fails
    # here: this command only asks whether the notices name every library.
    policy = load(str(Path(__file__).resolve().parent.parent / "infra/licences/licence-policy.json"))
    policy["failOnClass"] = {"library": [], "os": []}
    _, rows = evaluate(sbom, policy)
    missing = [c.get("name") for c, _, _ in library_rows(rows) if f"| {c.get('name')} |" not in notices]
    if not missing:
        print("OK: every Java library in the SBOM is in THIRD-PARTY-NOTICES.md.")
        return 0
    print(f"FAIL: {len(missing)} Java librar{'y' if len(missing) == 1 else 'ies'} not in THIRD-PARTY-NOTICES.md:", file=sys.stderr)
    for name in missing:
        print(f"  {name}", file=sys.stderr)
    print("Regenerate it: scripts/check-licences.py notices <sbom> > THIRD-PARTY-NOTICES.md", file=sys.stderr)
    return 1


def main(argv: list) -> int:
    if len(argv) < 3 or argv[1] not in ("check", "notices", "covered"):
        print(__doc__, file=sys.stderr)
        return 2
    default_policy = str(Path(__file__).resolve().parent.parent / "infra/licences/licence-policy.json")
    if argv[1] == "covered":
        return cmd_covered(argv[2], argv[3])
    policy = argv[3] if len(argv) > 3 else default_policy
    return cmd_check(argv[2], policy) if argv[1] == "check" else cmd_notices(argv[2], policy)


if __name__ == "__main__":
    sys.exit(main(sys.argv))
