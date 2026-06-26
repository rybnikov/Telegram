#!/bin/sh
set -eu

registry="${1:-docs/FORK_FEATURES.md}"

if [ "$registry" != "docs/FORK_FEATURES.md" ]; then
  echo "check-fork-anchors: registry path must be docs/FORK_FEATURES.md, got: $registry" >&2
  exit 2
fi

if [ ! -f "$registry" ]; then
  echo "check-fork-anchors: missing $registry" >&2
  exit 2
fi

if [ ! -x "./gradlew" ]; then
  echo "check-fork-anchors: run from repository root" >&2
  exit 2
fi

if [ -z "${JAVA_HOME:-}" ] && command -v /usr/libexec/java_home >/dev/null 2>&1; then
  JAVA_HOME=$(/usr/libexec/java_home -v 17)
  export JAVA_HOME
fi

if [ -n "${CHECK_FORK_ANCHORS_GRADLE_CMD:-}" ]; then
  exec sh -c "$CHECK_FORK_ANCHORS_GRADLE_CMD"
fi

exec ./gradlew :TMessagesProj:testHA_privateUnitTest --tests '*MergeRegressionCanaryTest'
