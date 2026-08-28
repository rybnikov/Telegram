# Foldogram Development Runbook

This document is the tracked, shareable development procedure for the Foldogram
fork. Keep local paths, device identifiers, signing passwords, and generated
service files out of this file. Put machine-specific notes in `CLAUDE.local.md`.

For upstream merges, do not duplicate the procedure here. Use
`docs/UPSTREAM_MERGE.md`, `docs/FORK_FEATURES.md`, and
`docs/MERGE_REPORT_TEMPLATE.md`.

## Branching

- Branch normal feature and fix work from `foldogram`.
- Do not branch new work from temporary feature branches unless the task
  explicitly requires stacked changes.
- Keep rehearsal or experimental merge branches local until the owner accepts the
  result.
- Do not push unless explicitly asked. For upstream merges, a push to
  `fork/foldogram` triggers Foldogram CI and must wait until the owner has
  accepted the current installed build after its version/commit was verified.

## Java And Gradle

- Source compatibility is Java 8.
- Use Java 17 to run Gradle:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17)
```

- Prefer explicit module tasks over broad Gradle invocations when validating a
  focused change.

## Build And Install

The beta/device-test build uses the `TMessagesProj_AppHockeyApp` module and the
package `com.rbnkv.foldogram.beta`:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :TMessagesProj_AppHockeyApp:compileAfatHA_privateJavaWithJavac
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :TMessagesProj_AppHockeyApp:installAfatHA_private
```

`installAfatHA_private` is the canonical physical-device task. When the signing
key matches, it upgrades the existing Foldogram Beta installation in place and
preserves its database. Before and after installing, verify that the target is
the beta package:

```bash
adb shell dumpsys package com.rbnkv.foldogram.beta
```

Local development and device smoke tests must never install over, clear, or
uninstall `com.rbnkv.foldogram`, which is the Google Play installation. The
stable and beta packages have independent app data; a beta database initialized
by the previous accepted Foldogram code is the existing-user baseline for local
migration tests.

Fresh-install testing also targets only `com.rbnkv.foldogram.beta`. Prefer an
emulator or isolated Android profile so the upgrade-test beta data remains
available. Clearing or uninstalling beta on a shared device destroys beta data
and requires explicit owner approval.

The release bundle uses the `TMessagesProj_App` module:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :TMessagesProj_App:bundleAfatRelease
```

This release artifact is for the Google Play workflow, not for local device
installation over the stable app.

Release version and signing overrides must come from environment variables,
GitHub secrets, or `local.properties`; never hardcode them in tracked files.

## Local Configuration

Use `local.properties` or environment variables for local-only configuration:

- Telegram API identifiers.
- Google services JSON paths or JSON payloads.
- Release keystore path, alias, and passwords.
- Any other machine-specific build override.

Do not commit generated service files, signing files, or local config.

## Secrets Policy

Never commit:

- `CLAUDE.local.md`.
- `local.properties`.
- Generated `google-services.json` files.
- Keystores or signing material such as `*.keystore` and `*.jks`.
- API keys, tokens, credentials, or copied secret payloads.

Before every commit, inspect staged files:

```bash
git diff --cached --name-only
git diff --cached
```

If a secret-like file appears in the index, unstage it before committing.

## Commit Rules

- Commit messages must not contain AI traces such as `Claude`, `AI`,
  `Co-Authored`, or `Generated`.
- Keep commits focused: one logical change per commit.
- Do not amend or rewrite accepted history unless explicitly asked.

Check commit messages before reporting completion:

```bash
git log --format=%B foldogram..HEAD | rg -i "Claude|AI|Co-Authored|Generated"
```

No output is the expected result.

## Required Gates

Run the merge canary before accepting a merge-sensitive change:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :TMessagesProj:testHA_privateUnitTest --tests '*MergeRegressionCanaryTest'
```

Run the four CI gates before merging into `foldogram` or preparing a release:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :TMessagesProj:testHA_privateUnitTest
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :TMessagesProj:compileHA_privateJavaWithJavac
JAVA_HOME=$(/usr/libexec/java_home -v 17) sh scripts/check-fork-anchors.sh
sh scripts/check-secrets-policy.sh
```

If any gate fails, fix the underlying invariant or stop and report the blocker.
