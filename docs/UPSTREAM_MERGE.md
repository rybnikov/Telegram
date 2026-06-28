# Upstream Merge Runbook

This is the executable merge procedure for Foldogram. It is written for agents,
not as informal notes. Follow it when syncing `foldogram` with upstream
DrKLO/Telegram.

`AGENTS.md` and the `CLAUDE.md -> AGENTS.md` symlink only route agents to tracked
docs. Mandatory merge procedures live in `docs/`. `CLAUDE.local.md` is local,
ignored, and must not be committed. If local agent memory and tracked docs
disagree, tracked docs are authoritative.

## Remote Layout

The repository uses these remotes:

```bash
git remote -v
```

Expected:

```text
origin  https://github.com/DrKLO/Telegram.git
fork    https://github.com/rybnikov/Telegram.git
```

`origin` is upstream. `fork` is the Foldogram fork. Do not swap them silently. If
the remotes differ, stop and ask before continuing.

## Lifecycle

Always merge upstream through a dedicated merge branch. Do not apply an upstream
snapshot directly on top of `foldogram`.

```bash
git fetch --prune origin
git fetch --prune fork

git checkout foldogram
git status --short
git pull --ff-only fork foldogram

git checkout -b merge/upstream-<version>-into-foldogram
git merge origin/master
```

If `git status --short` is not clean before the merge, stop. Do not stash or
overwrite user work unless explicitly asked.

## Required Inputs

Before resolving conflicts, record these refs:

```bash
OLD_BASE=<previous upstream base used by the last accepted Foldogram sync>
NEW_BASE=<new upstream commit being merged, usually origin/master>
FORK_TIP=<foldogram commit before this merge>
```

If `OLD_BASE` is unknown, use the last documented upstream base or ask the owner.
Do not guess from a noisy history if the answer changes the audit.

## Audit Commands

Run both checks after conflict resolution and before accepting the merge.

```bash
scripts/merge-delta-audit.sh "$OLD_BASE" "$NEW_BASE" "$FORK_TIP"
```

`merge-delta-audit.sh` is a diagnostic radar. It shows fork-added source lines
that may have disappeared relative to a new upstream base. It is intentionally
noisy and advisory. Use it to decide what to inspect; do not treat a clean or
noisy result as final proof.

```bash
scripts/check-fork-anchors.sh docs/FORK_FEATURES.md
```

`check-fork-anchors.sh` is the strict gate. It delegates to the registry-driven
`MergeRegressionCanaryTest`, which parses the `json` blocks in
`docs/FORK_FEATURES.md` and fails if protected anchors disappear. The shell
wrapper intentionally has no Python or jq dependency.

## Conflict Resolution Algorithm

For every conflict:

1. Identify the feature-id.
2. Open `docs/FORK_FEATURES.md`.
3. Read the feature's Human explanation, Invariant, Conflict policy, and `json`
   anchors.
4. Inspect the new upstream code.
5. Decide one of three outcomes.
6. Confirm using the tests listed for that feature.

Valid outcomes:

```text
upstream already satisfies invariant -> remove obsolete Foldogram hook
upstream rewrote surrounding code -> adapt the Foldogram hook to the new shape
upstream does not satisfy invariant -> preserve the invariant in Foldogram code
```

Invalid outcomes:

```text
choose ours blindly
choose theirs blindly
restore old lines without understanding the new upstream code
delete a hook because the compiler passes
lower an anchor threshold to make CI green
```

LaunchActivity tablet/layout transitions are merge-sensitive. Do not reintroduce
upstream's immediate `AndroidUtilities.resetTabletFlag()`, `invalidateTabletMode()`,
or `checkLayout()` calls inside `LaunchActivity.onConfigurationChanged`; Foldogram
must keep configuration changes measure-driven through
`updateDisplaySizeFromRootMeasure -> scheduleWindowWidthChanged ->
onWindowWidthChanged` so `shouldFreezeTabletModeChange` is not bypassed.

Feature-id comes from one of:

```text
// FOLDOGRAM(<feature-id>)
// FOLDOGRAM-EXT-PREVIEW
docs/FORK_FEATURES.md anchors
the file/symbol touched by the conflict
```

Legacy `FOLDOGRAM-EXT-PREVIEW` markers map to `ext-preview`.

## Required Gates

Before a merge branch can be accepted, all gates must pass:

```bash
scripts/check-fork-anchors.sh docs/FORK_FEATURES.md
scripts/check-secrets-policy.sh
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :TMessagesProj:testHA_privateUnitTest
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :TMessagesProj:compileHA_privateJavaWithJavac
```

If a required script is missing or not executable, the gate failed. Restore the
script from this runbook/registry work before accepting the merge.

## Stop Rules

Stop and ask the owner instead of improvising when any of these happen:

```text
protected anchor disappeared and the invariant is not clearly preserved
canary is red
unit tests are red after a conflict resolution
database migration conflict touches LAST_DB_VERSION or external_previews_v1
registry description contradicts the real code
upstream touched a registry file but canary stayed green
secret/config files are added, removed, or changed unexpectedly
release workflow semantics change
```

A green canary is necessary but not sufficient. If upstream changed files listed
in `docs/FORK_FEATURES.md`, inspect the relevant feature entry even when tests
are green.

## Threshold Rule

Anchor thresholds and `min_count` values are merge guards. Do not lower them
inside a conflict-resolution commit.

A threshold may change only in a dedicated baseline commit that also updates
`docs/FORK_FEATURES.md` and explains the intentional removal:

```text
Update fork protection baseline after intentional removal: <reason>
```

If a threshold must be lowered because upstream now implements the invariant,
first prove the invariant with code inspection or a test, then update the
registry in that dedicated commit.

Current canary baseline is intentionally pinned to 13 feature blocks and 111
anchors. TODO: make this count dynamic after the registry/CI flow is stable. Until
then, update the pinned count only in the same dedicated registry baseline commit.

## Database Migration Rule

Released Foldogram database migration numbers are frozen. Once a build has
shipped with a fork migration at a specific `user_version`, that migration must
stay at that number forever. Existing users may already be parked at that
version; renumbering it can either rerun a non-idempotent DDL statement or skip a
new upstream migration.

When upstream increments `MessagesStorage.LAST_DB_VERSION`, append the new
upstream migration above the released Foldogram floor:

```text
current released Foldogram LAST_DB_VERSION = N
new upstream migration = if (version == N) { ...; PRAGMA user_version = N + 1; }
new Foldogram LAST_DB_VERSION = N + 1
```

If upstream has multiple new migrations, append them in order as `N + 1`,
`N + 2`, and so on. Never insert a new migration at a `user_version` that
released Foldogram users may already have passed.

All Foldogram DDL must be idempotent:

```text
CREATE TABLE/INDEX -> CREATE ... IF NOT EXISTS
ADD COLUMN          -> executeNoException(database, "ALTER TABLE ... ADD COLUMN ...")
```

If upstream also changes migration blocks around `external_previews_v1`, stop and
inspect:

```text
MessagesStorage.LAST_DB_VERSION
DatabaseMigrationHelper external_previews_v1 migration
ExternalPreviewStorage.createTables
ExternalPreviewStorage.purgeForFormatUpgrade
PreviewRepository.EXTERNAL_PREVIEW_FORMAT_VERSION
```

Never resolve database migration conflicts with `ours` or `theirs`. Preserve
released fork migration numbers, append new upstream migrations above them, and
adapt the surrounding DDL explicitly.

Every merge report must include a DB upgrade trace for each parked
`user_version` that released users can have. For each parked version, list which
migrations run, which ones are skipped, and why the upgrade is safe.

Worked example, upstream 12.8.1 `web_browser_settings`:

Correct:

```text
if (version == 174) {
    executeNoException(database, "ALTER TABLE external_previews_v1 ADD COLUMN extra TEXT");
    PRAGMA user_version = 175;
}
if (version == 175) {
    CREATE TABLE IF NOT EXISTS web_browser_settings (...);
    PRAGMA user_version = 176;
}
LAST_DB_VERSION = 176
```

A user already on released Foldogram `user_version=175` receives only
`web_browser_settings`. A fresh install or older upgrade still passes through
the frozen Foldogram preview migration first, then the appended upstream
migration.

Incorrect:

```text
if (version == 174) { CREATE TABLE web_browser_settings (...); -> 175 }
if (version == 175) { ALTER TABLE external_previews_v1 ADD COLUMN extra TEXT; -> 176 }
```

That renumbers the shipped fork migration. A released user parked on 175 may
rerun the preview `ADD COLUMN` and crash on a duplicate column, or may miss the
new upstream table depending on the exact conflict resolution.

## Secrets Policy During Merge

Never add newly generated app credentials or local notes to git:

```text
CLAUDE.local.md
TMessagesProj_App/google-services.json
TMessagesProj_AppHockeyApp/google-services.json
local generated google-services.json files
keystore files
real API keys
```

If upstream includes tracked sample or legacy config files, classify them in the
merge report. Do not broaden tracked secrets/config surface without explicit
review.

Before accepting a merge, run:

```bash
git status --short
scripts/check-secrets-policy.sh
```

The reviewed allowlist lives in `scripts/check-secrets-policy.sh`. Raw
`git ls-files | grep -E '(^|/)google-services\.json$|\.keystore$|\.jks$|\.p12$'`
is only a diagnostic aid.

## Merge Report

Every upstream merge must end with a report before it is merged back to
`foldogram`. Use `docs/MERGE_REPORT_TEMPLATE.md` as the report skeleton.

Required fields:

```text
Merge branch:
Old upstream base:
New upstream base:
Foldogram tip before merge:

merge-delta-audit:
check-fork-anchors:
check-secrets-policy:
testHA_privateUnitTest:
compileHA_privateJavaWithJavac:

Feature matrix:
ext-preview:
nav-recovery:
fold-tablet:
cutout:
android-auto:
maps-live-location:
places-index:
db-preview:
browser-iv:
identity:
share-shortcuts:
ci-release:
secrets-policy:

Conflicts resolved:
Known residual risks:
Manual device smoke needed:
```

Do not merge the branch into `foldogram` and do not push it until the owner
accepts the report.

## Final Acceptance

After all gates pass and the report is accepted:

```bash
git checkout foldogram
git merge --no-ff merge/upstream-<version>-into-foldogram
git status --short
```

Push only after explicit owner approval.
