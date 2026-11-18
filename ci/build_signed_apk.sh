#!/usr/bin/env bash
set -euo pipefail

version="$(sed -n 's/.*val baseVersionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts)"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(\.[0-9]+)?$ ]] || {
  echo "ERROR: Invalid Handler base version." >&2; exit 1;
}
if [[ -n "${CI_COMMIT_TAG:-}" && "$CI_COMMIT_TAG" != "v$version" ]]; then
  echo "ERROR: Release tag must match v$version." >&2; exit 1
fi

: "${WEARTAK_RELEASE_KEYSTORE_B64:?Missing production keystore}"
: "${RELEASE_STORE_PASSWORD:?Missing store password}"
: "${RELEASE_KEY_ALIAS:?Missing key alias}"
: "${RELEASE_KEY_PASSWORD:?Missing key password}"
umask 077
trap 'rm -f app/weartak.keystore signing.properties' EXIT
printf '%s' "$WEARTAK_RELEASE_KEYSTORE_B64" | base64 -d > app/weartak.keystore
: > signing.properties
export WEARTAK_RELEASE_KEYSTORE_FILE=app/weartak.keystore
export WEARTAK_RELEASE_STORE_PASSWORD="$RELEASE_STORE_PASSWORD"
export WEARTAK_RELEASE_KEY_ALIAS="$RELEASE_KEY_ALIAS"
export WEARTAK_RELEASE_KEY_PASSWORD="$RELEASE_KEY_PASSWORD"
keytool -list -keystore app/weartak.keystore -storepass:env RELEASE_STORE_PASSWORD \
  -alias "$RELEASE_KEY_ALIAS" >/dev/null
./gradlew --no-daemon :app:assembleRelease

dir=app/build/outputs/apk/release
shopt -s nullglob
apks=("$dir"/*.apk)
if (( ${#apks[@]} != 1 )); then
  echo "ERROR: Expected exactly one release APK." >&2; exit 1
fi
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk-linux}}"
apksigner="$(find "$sdk/build-tools" -type f -name apksigner | sort -V | tail -1)"
[[ -x "$apksigner" ]] || { echo "ERROR: apksigner missing." >&2; exit 1; }
certs="$("$apksigner" verify --print-certs "${apks[0]}")"
actual="$(sed -n 's/^Signer #[0-9]* certificate SHA-256 digest: //p' <<< "$certs" | tr '[:upper:]' '[:lower:]')"
expected=1579b600cd84e619e76a91c2db6b031938c999bc2aad926b9a51ec2b166d348d
[[ "$actual" == "$expected" ]] || { echo "ERROR: Unexpected signing certificate." >&2; exit 1; }

apk="$dir/WearTAK-Plugin-Handler-$version-p${CI_PIPELINE_IID:?}.apk"
mv "${apks[0]}" "$apk"
# Keep Gradle metadata matched to the verified, renamed APK.
sed -i "s/\"outputFile\": \"[^\"]*\"/\"outputFile\": \"${apk##*/}\"/" "$dir/output-metadata.json"
sha256sum "$apk" > "$dir/verified-apk.sha256"
