#!/bin/sh
set -eu

failures="$(mktemp)"
tracked_files="$(mktemp)"
api_key_matches="$(mktemp)"
trap 'rm -f "$failures" "$tracked_files" "$api_key_matches"' EXIT

add_failure() {
  printf '%s\n' "$1" >>"$failures"
}

require_gitignore_entry() {
  pattern="$1"
  if ! grep -Fx "$pattern" .gitignore >/dev/null 2>&1; then
    add_failure ".gitignore missing required entry: $pattern"
  fi
}

is_allowed_secret_path() {
  path="$1"
  case "$path" in
    TMessagesProj/config/release.keystore) return 0 ;;
    TMessagesProj/google-services.json) return 0 ;;
    TMessagesProj_AppHuawei/google-services.json) return 0 ;;
    TMessagesProj_AppStandalone/google-services.json) return 0 ;;
    TMessagesProj/jni/boringssl/crypto/pkcs8/test/*.p12) return 0 ;;
    TMessagesProj/src/test/resources/preview/tt_video.html) return 0 ;;
    TMessagesProj/src/test/resources/preview/tt_video2.html) return 0 ;;
  esac
  return 1
}

require_gitignore_entry "CLAUDE.md"
require_gitignore_entry "google-services.json"
require_gitignore_entry "**/google-services.json"

if git ls-files --error-unmatch CLAUDE.md >/dev/null 2>&1; then
  add_failure "CLAUDE.md must not be tracked"
fi

if git ls-files --error-unmatch TMessagesProj_App/google-services.json >/dev/null 2>&1; then
  add_failure "TMessagesProj_App/google-services.json must not be tracked"
fi

if git ls-files --error-unmatch TMessagesProj_AppHockeyApp/google-services.json >/dev/null 2>&1; then
  add_failure "TMessagesProj_AppHockeyApp/google-services.json must not be tracked"
fi

if [ -n "${CHECK_SECRETS_LS_FILES_CMD:-}" ]; then
  sh -c "$CHECK_SECRETS_LS_FILES_CMD" >"$tracked_files"
else
  git ls-files >"$tracked_files"
fi

if [ -n "${CHECK_SECRETS_GREP_AIZA_CMD:-}" ]; then
  sh -c "$CHECK_SECRETS_GREP_AIZA_CMD" >"$api_key_matches" || true
else
  git grep -I -n -o 'AIza[A-Za-z0-9_-]*' -- . >"$api_key_matches" || true
fi

while IFS= read -r path; do
  case "$path" in
    CLAUDE.md)
      add_failure "CLAUDE.md must not be tracked"
      ;;
    *google-services.json|*.keystore|*.jks|*.p12)
      if ! is_allowed_secret_path "$path"; then
        add_failure "tracked secret-like file is not allowlisted: $path"
      fi
      ;;
  esac
done <"$tracked_files"

while IFS= read -r match; do
  path=${match%%:*}
  if ! is_allowed_secret_path "$path"; then
    add_failure "tracked Google API key outside allowlist: $match"
  fi
done <"$api_key_matches"

if [ -s "$failures" ]; then
  echo "check-secrets-policy: failed" >&2
  cat "$failures" >&2
  exit 1
fi

echo "check-secrets-policy: ok"
