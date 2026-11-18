# WearTAK Handler

## Purpose and Capabilities

WearTAK Handler is a standalone Wear OS plugin app that estimates animal breathing rate and
movement intensity from accelerometer data while a watch is held or secured against a dog or
horse. Its launcher app works without ATAK Companion. Measurement and movement alerts stay in
this plugin; the WearTAK host only discovers its plugin service.

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
Release signing uses the WearTAK sideload signing properties or CI secrets; no signing key is
included in this repository.

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
