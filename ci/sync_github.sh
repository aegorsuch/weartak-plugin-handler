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

export HANDLER_GITHUB_TOKEN GIT_TERMINAL_PROMPT=0
# Supply credentials to Git directly; CI runners need not execute an askpass file.
helper='!f() {
  if [ "$1" = get ]; then
    printf "username=x-access-token\npassword=%s\n\n" "$HANDLER_GITHUB_TOKEN"
  fi
}; f'
github_git() {
  git -c credential.helper= -c credential.useHttpPath=true \
    -c "credential.$target.helper=$helper" -c http.followRedirects=false "$@"
}

if [ -n "${CI_COMMIT_TAG:-}" ]; then
  printf '%s\n' "$CI_COMMIT_TAG" | grep -Eq '^v[0-9]+\.[0-9]+\.[0-9]+(\.[0-9]+)?$' ||
    fail "Not an eligible version tag."
  tag="refs/tags/$CI_COMMIT_TAG"
  github_git fetch --no-tags "$target" "$branch"
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

github_git push --no-follow-tags "$target" "$source:$destination"
local_object="$(git rev-parse "$source")"
listing="$(github_git ls-remote --refs "$target" "$destination")"
remote_object="$(printf '%s\n' "$listing" | cut -f 1)"
[ "$remote_object" = "$local_object" ] || fail "Public ref did not match the pushed object."
