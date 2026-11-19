# WearTAK Handler

## Purpose and Capabilities

WearTAK Handler is a standalone Wear OS plugin app that estimates animal breathing rate and
movement intensity from accelerometer data while a watch is held or secured against a dog or
horse. Its launcher app works without ATAK Companion. Measurement and movement alerts stay in
this plugin; the WearTAK host only discovers its plugin service. App version is 5.8.4 to match
the current WearTAK release.

## Status

Experimental. Estimates are motion-derived, not veterinary measurements or medical advice.
Algorithm thresholds have not been validated on animals or across watch models.

## Point of Contacts

Maintainer and support contacts are not documented.

## Ports Required

No runtime network ports. WearTAK plugin discovery uses Android Binder.

## Equipment Required

- JDK 17
- Android SDK API 34
- Wear OS watch with an accelerometer

## Equipment Supported

The app targets API 34 and declares minimum API 30. Watch-specific accuracy and fit have not
been validated. Place the watch firmly against the animal's side; loose or changing placement
can invalidate estimates.

## Compilation

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The generated debug APK is under `app/build/outputs/apk/debug/`. The SDK AARs in `app/libs/`
are built from the pinned SDK source revision documented in
[`third_party/weartak-plugin-sdk/NOTICE.md`](third_party/weartak-plugin-sdk/NOTICE.md).
GitHub Actions runs unit tests and a debug build. TAK.gov alone signs releases with the
WearTAK production key. A protected `main` push produces a verified
`WearTAK-Plugin-Handler-5.8.4-p<N>.apk` (`N` is the project pipeline IID), fast-forwards
GitHub `main`, and updates the rolling GitHub release when publication is enabled. A protected
version tag such as `v5.8.4` must match the app base version and point to a commit already in
public `main` before it is mirrored. Tag pipelines retain a signed artifact on TAK.gov but
do not replace the rolling GitHub release. Only reviewed public content belongs on `main`.

Release setup:

1. Protect `main` and restrict version tag creation to release maintainers on
   [TAK.gov](https://git.tak.gov/weartak-plugins/weartak-plugin-handler/-/settings/repository).
   Signing variables are inherited from the WearTAK plugin group; do not put signing keys on GitHub.
2. In [TAK.gov CI/CD settings](https://git.tak.gov/weartak-plugins/weartak-plugin-handler/-/settings/ci_cd),
   provision `HANDLER_GITHUB_TOKEN` as a masked, hidden, protected, non-expanding service
   credential limited to the Handler mirror. It needs GitHub Contents write for refs and
   releases, and Workflows write to mirror changes under `.github/workflows/`. If GitHub
   restricts pushes to `main`, allow this service identity to push without allowing force pushes.
3. Set protected `HANDLER_GITHUB_SYNC_ENABLED=true` for automatic ref sync.
   To enable rolling publication, pre-create `TAK.gov-Forge-CI-CD-Pipeline-Release` on a
   commit already in GitHub `main` and set protected `HANDLER_GITHUB_RELEASE_ENABLED=true`.
   Enable before the next approved release push, not to test publication.
4. Set `RELEASE_TESTER` to an authorized GitHub username in
   [Actions variables](https://github.com/aegorsuch/weartak-plugin-handler/settings/variables/actions).
   After a verified publish, the release-testing workflow opens one checklist per pipeline.
5. Create a separate [fine-grained GitHub token](https://github.com/settings/personal-access-tokens/new)
   limited to `aegorsuch/weartak-wearos`, with repository **Actions: write** (and automatic
   Metadata read) only. Store it as `MARKET_CATALOG_DISPATCH_TOKEN` in
   [TAK.gov CI/CD settings](https://git.tak.gov/weartak-plugins/weartak-plugin-handler/-/settings/ci_cd):
   masked, hidden, protected, and non-expanding. Never reuse or broaden `HANDLER_GITHUB_TOKEN`.
   The destination's `main` branch must contain the dispatch-enabled
   [Market catalog workflow](https://github.com/aegorsuch/weartak-wearos/blob/main/.github/workflows/publish-market-catalog.yml).

After APK digest/readback, published stable release confirmation, and stale owned asset
cleanup succeed, publication notifies testers and independently requests an immediate Market
catalog refresh. The Market hook sends only `{"ref":"main"}` to the fixed destination
workflow dispatch endpoint, refuses redirects, times out after 30 seconds, and requires HTTP
204. Skipped/stale, failed, debug, and prerelease builds never trigger it. The existing hourly
catalog fallback remains unchanged; accepted dispatch does not mean catalog refresh has finished.

A missing Market token or failed notification fails the publish job **after the release stays
published**. Configure the token/workflow before activation. Retry the same GitLab publish job
after fixing notification access/connectivity: it rechecks the APK and release, finishes any
remaining cleanup, and resends notifications without duplicating release assets or tester
checklists. A retry superseded by a newer pipeline skips notifications.

Publisher and checklist tooling adapted from the GPLv3
[WearTAK LACE pipeline](https://github.com/aegorsuch/weartak-plugin-lace/tree/main/ci).

## Developer Notes

- The estimator uses a 30-second accelerometer window, axis-wise autocorrelation, and reports
  rates only from 6–120 breaths/min when correlation confidence is at least 0.35.
- Breathing estimates are withheld during high movement. Movement RMS is classified as quiet
  below 0.35 m/s², active below 1.25 m/s², and high at or above 1.25 m/s². A local high-movement
  alert appears after three consecutive high windows. These are engineering heuristics, not
  species-specific or clinically validated thresholds.
- Sampling runs only while the launcher activity is visible and the wearer starts monitoring.
  Stopping or leaving the activity stops sensor sampling.
- Confidence and movement values depend on sensor placement, sampling rate, gait, and animal
  physiology. Validate against an independent reference before operational use.
- SDK license notices are preserved under `third_party/weartak-plugin-sdk/`.

## License

This project is licensed under GNU GPLv3 or later. See [`LICENSE`](LICENSE) and the SDK notices.
