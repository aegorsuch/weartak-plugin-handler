#!/bin/sh
set -eu

fail() { printf 'ERROR: %s\n' "$1" >&2; exit 1; }
target=https://github.com/aegorsuch/weartak-plugin-handler.git
branch=refs/heads/main

if [ "${CI_SERVER_HOST:-}" != git.tak.gov ] ||
   [ "${CI_PROJECT_ID:-}" != 7062 ] ||
   [ "${CI_PROJECT_PATH:-}" != weartak-plugins/weartak-plugin-handler ] ||
   [ "${CI_COMMIT_REF_PROTECTED:-}" != true ] ||
   [ "${CI_PIPELINE_SOURCE:-}" != push ] ||
   [ "${HANDLER_GITHUB_SYNC_ENABLED:-}" != true ]; then
  fail "Not an enabled protected Handler push pipeline."
fi
printf '%s\n' "${CI_COMMIT_SHA:-}" | grep -Eq '^[0-9a-f]{40}$' || fail "Invalid commit SHA."
: "${HANDLER_GITHUB_TOKEN:?Set a protected GitHub service credential for Handler}"

umask 077
askpass="$(mktemp)"
trap 'rm -f "$askpass"' EXIT
cat > "$askpass" <<'EOF'
#!/bin/sh
case "$1" in
  *Username*) printf '%s\n' x-access-token ;;
  *Password*) printf '%s\n' "$HANDLER_GITHUB_TOKEN" ;;
  *) exit 1 ;;
esac
EOF
chmod +x "$askpass"
export GIT_ASKPASS="$askpass" GIT_TERMINAL_PROMPT=0

if [ -n "${CI_COMMIT_TAG:-}" ]; then
  printf '%s\n' "$CI_COMMIT_TAG" | grep -Eq '^v[0-9]+\.[0-9]+\.[0-9]+(\.[0-9]+)?$' ||
    fail "Not an eligible version tag."
  tag="refs/tags/$CI_COMMIT_TAG"
  git -c credential.helper= fetch --no-tags "$target" "$branch"
  git merge-base --is-ancestor "$CI_COMMIT_SHA" FETCH_HEAD ||
    fail "Tag target is not in public GitHub main. Sync main first."
  [ "$(git rev-parse "$tag^{commit}")" = "$CI_COMMIT_SHA" ] ||
    fail "Tag does not match the pipeline commit."
  source="$tag"
  destination="$tag"
elif [ "${CI_COMMIT_BRANCH:-}" = main ]; then
  source="$CI_COMMIT_SHA"
  destination="$branch"
else
  fail "Only main and protected version tags are eligible for public sync."
fi

git -c credential.helper= push --no-follow-tags "$target" "$source:$destination"
local_object="$(git rev-parse "$source")"
remote_object="$(git -c credential.helper= ls-remote --refs "$target" "$destination" | cut -f 1)"
[ "$remote_object" = "$local_object" ] || fail "Public ref did not match the pushed object."
