---
name: upstream-merge
description: "Use when merging upstream DrKLO/Telegram into the foldogram fork. Branch strategy, beta-only device acceptance, per-conflict feature-id resolution, DB migration rules, CI gates, and merge report. Do NOT use for normal feature work."
---

# Upstream Merge Skill

Use this skill only for upstream DrKLO/Telegram sync work.

## Workflow

1. Start from `foldogram` and create a dedicated merge branch such as
   `merge/upstream-<version>`.
2. Fetch upstream and merge the real upstream history. Do not apply source
   snapshots over `foldogram`.
3. For every conflict, map the touched code to a `feature-id` in
   `docs/FORK_FEATURES.md`.
4. Read the feature invariant and conflict policy before editing.
5. Inspect the new upstream code:
   if upstream now implements the invariant, discard the obsolete fork hook;
   if upstream rewrote the surrounding code, adapt the hook;
   otherwise preserve the fork behavior.
6. Never resolve conflicts with blind `ours` or `theirs`.
7. For `LaunchActivity.onConfigurationChanged`, do not reintroduce upstream's
   immediate `AndroidUtilities.resetTabletFlag()`, `invalidateTabletMode()`, or
   `checkLayout()` calls. Foldogram tablet/layout transitions must stay
   measure-driven through `updateDisplaySizeFromRootMeasure`,
   `scheduleWindowWidthChanged`, and `onWindowWidthChanged`.
8. Before merging back to `foldogram`, perform the required device test from
   `docs/UPSTREAM_MERGE.md` using only `com.rbnkv.foldogram.beta` from
   `:TMessagesProj_AppHockeyApp:installAfatHA_private`. Upgrade existing beta
   data in place, then test a fresh beta install in an isolated profile/emulator
   or after explicit approval to clear beta data. Verify package/version and
   candidate provenance before owner acceptance. Never install over, clear, or
   uninstall the Google Play package `com.rbnkv.foldogram`.
9. Do not push an upstream merge to `fork/foldogram` before device acceptance.
   Pushes to `foldogram` trigger Foldogram CI, so pushing is part of the
   post-acceptance release boundary.

## Database Migration Rule

Released Foldogram migration numbers are frozen. New upstream migrations are
appended above the released Foldogram floor. Fork DDL must be idempotent:
`CREATE ... IF NOT EXISTS`, and `ADD COLUMN` through `executeNoException`.

For every DB conflict, include a parked-user-version trace in the merge report:
which migrations run, which ones are skipped, and why the upgrade is safe.

## Stop Rules

- Canary red: restore the invariant. Do not lower thresholds in a conflict
  commit.
- DB migration ambiguity: stop and produce the user-version trace before
  continuing.
- Secret-like file staged: unstage it and fix the source.
- Missing fork feature invariant: stop and ask for review.
- A local/device artifact resolves to `com.rbnkv.foldogram` instead of
  `com.rbnkv.foldogram.beta`: stop before installing it.
- Device acceptance is stale or unverified: install the current build, verify
  the beta package/version and candidate provenance, and wait for fresh owner
  acceptance before pushing `fork/foldogram` or starting release.

## Gates

```bash
JAVA_HOME="$JDK21_HOME" ./gradlew :TMessagesProj:testHA_privateUnitTest
JAVA_HOME="$JDK17_HOME" ./gradlew :TMessagesProj:compileHA_privateJavaWithJavac
JAVA_HOME="$JDK21_HOME" sh scripts/check-fork-anchors.sh
sh scripts/check-secrets-policy.sh
scripts/merge-delta-audit.sh <old-upstream-base> <new-upstream-base> <pre-merge-foldogram-tip>
```

Set `JDK21_HOME` and `JDK17_HOME` to local JDK installations. Robolectric 4.16
needs Java 21 to follow target SDK 36; never make the gate green by pinning tests
to an older Android SDK.

The delta-audit third argument is the pre-merge `foldogram` tip, not merge
`HEAD`. The script is an advisory pre-merge collision-lister, not a post-merge
loss detector; canary coverage and manual review verify hook survival.

Full reference: `docs/UPSTREAM_MERGE.md`.
Registry: `docs/FORK_FEATURES.md`.
Report template: `docs/MERGE_REPORT_TEMPLATE.md`.
