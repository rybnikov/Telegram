# Foldogram Agent Routing

This file is the always-on entry point for agents working in this repository.
Detailed procedures live in tracked docs; this file only routes.

## Routes

- Development, builds, installs, tests, releases, and normal code changes:
  `docs/DEVELOPMENT.md`.
- Upstream DrKLO/Telegram merges: `docs/UPSTREAM_MERGE.md`.
- Fork feature invariants and merge anchors: `docs/FORK_FEATURES.md`.
- Merge reporting: `docs/MERGE_REPORT_TEMPLATE.md`.

## Always-On Rules

- Branch normal work from `foldogram`.
- Do not push unless explicitly asked.
- Keep commit messages free of AI traces such as `Claude`, `AI`,
  `Co-Authored`, or `Generated`.
- Never commit local notes, generated Google services files, signing keys,
  `local.properties`, credentials, or copied secret payloads.
- Before accepting an upstream merge, run the merge canary, the four CI gates,
  and the delta audit required by `docs/UPSTREAM_MERGE.md`.
- If tracked docs and local memory disagree, tracked docs are authoritative.
