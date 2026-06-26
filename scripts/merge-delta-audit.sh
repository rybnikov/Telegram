#!/usr/bin/env bash
# Advisory diagnostic, NOT a gate.
# This script surfaces fork-added source lines that may need merge review.
# Acceptance is enforced by check-fork-anchors.sh / MergeRegressionCanaryTest.
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "Usage: $0 <old-base> <new-base> <fork-tip>" >&2
  echo "Advisory diagnostic, NOT a gate: prints fork-added source lines that existed against old-base but disappear against new-base." >&2
  exit 2
fi

old_base="$1"
new_base="$2"
fork_tip="$3"
source_path="TMessagesProj/src/main/java"

for rev in "$old_base" "$new_base" "$fork_tip"; do
  git rev-parse --verify "${rev}^{commit}" >/dev/null
done

old_delta="$(mktemp)"
new_delta="$(mktemp)"
trap 'rm -f "$old_delta" "$new_delta"' EXIT

collect_added_lines() {
  local base="$1"
  local tip="$2"

  git diff --no-ext-diff --no-renames --unified=0 "${base}..${tip}" -- "$source_path" |
    awk '
      /^diff --git / {
        file = $4
        sub(/^b\//, "", file)
        next
      }
      /^\+\+\+ / || /^--- / {
        next
      }
      /^\+/ {
        line = substr($0, 2)
        if (line !~ /^[[:space:]]*$/) {
          print file "\t" line
        }
      }
    ' |
    sort -u
}

collect_added_lines "$old_base" "$fork_tip" >"$old_delta"
collect_added_lines "$new_base" "$fork_tip" >"$new_delta"

echo "Fork-added lines present in ${old_base}..${fork_tip} but absent in ${new_base}..${fork_tip}:"
comm -23 "$old_delta" "$new_delta"
