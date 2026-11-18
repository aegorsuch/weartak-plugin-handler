## WearTAK Handler release test

- [ ] Install the APK on a supported Wear OS watch and verify WearTAK can discover Handler.
- [ ] Launch Handler on the watch without ATAK Companion. Confirm no score is initially available.
- [ ] Start monitoring. After warm-up, compare the scores from a stationary mount and a moving mount.
- [ ] Confirm sustained high movement shows a local warning without vibrating the mounted watch.
- [ ] Leave the activity and let the display turn off. Move the watch, then reopen Handler and verify that the score continued to update.
- [ ] With a compatible WearTAK host, verify outbound exertion uses Handler's fresh score while monitoring, preserves other metrics, and returns to native behavior when stopped.
- [ ] Stop monitoring and confirm the score clears. Record watch model, WearTAK version and observations.

**Motion-derived exertion** is movement RMS scaled to 2.5 m/s² (0–100). It is not
a measure of fatigue or heat stress. Invalid input or a sample gap over 250 ms
withholds the override until a new usable 30-second window. A score older than 10
seconds is also unavailable. A usable quiet signal can legitimately score zero.
An accelerometer alone cannot reliably distinguish a loose strap or an off-harness
watch from a mounted watch. Stop monitoring when removing the watch.

### Full screen-off watch session

- [ ] Record watch model, Wear OS/build, APK revisions, mount, workload and intended
  session length. Run one full unplugged session with display off (no active
  charging, fitness tracking, or changes to radio settings). Use wireless ADB
  after stopping if possible. Do not exercise an animal solely to force an alert.
- [ ] Stop Handler normally. With [ADB](https://developer.android.com/tools/adb),
  collect `adb logcat -d -s HarnessMonitoring:I`. Record sample count, rejected
  count, number of sample gaps over 250 ms, largest sample and callback gaps,
  startup and tail callback delay.
  An interrupted run without a summary is not a successful full session.
- [ ] Record battery start/end percentage and charge counter if available. Report
  percentage points per hour (and mAh/hour if supported) over elapsed duration.
  Compare against an unplugged, screen-off, Handler-off run of equal length under
  comparable conditions on the same watch. Report ambient and radio differences.
- [ ] On a compatible WearTAK host, compare native outbound exertion before start,
  during a valid Handler window, and during missing/invalid input and after stop.
  Confirm native fallback, unrelated telemetry unchanged, and no Companion needed.

### Labeled replay

Use locally recorded accelerometer data; do not check field recordings into the
repository. One contiguous, single-label segment per CSV. Use at least 30 seconds
to score and at least 40 seconds to observe a sustained warning; shorter inputs
are reported without a score. Name each file
`<dog|horse>_<rest|walk|trot-run|loose-strap|off-harness>_<session-id>.csv`.
Use monotonic sensor timestamps in nanoseconds, axes and hardware full-scale range
in m/s², and `true`/`false` reliability:

```csv
timestamp_ns,x_mps2,y_mps2,z_mps2,reliable,range_mps2
40000000,0.12,0.07,9.81,true,39.2
```

For paired accelerometer/gyroscope recordings, append
`gyro_x_rad_s,gyro_y_rad_s,gyro_z_rad_s` to the header and supply synchronized
angular velocities in radians per second. Leave all three gyro fields blank for
a missing reading; accelerometer-only scoring still runs. Replay reports
rotation RMS and repetition frequency where periodicity is clear. Repetition
frequency is not a calibrated stride count. Compare securely mounted clips with
labeled loose-strap clips before treating irregular rotation as a mount fault.

For the flank experiment, use a securely fitted mount on the dog's side with the
watch face approximately parallel to the flank. Append `-flank-z` to the file
stem only after verifying that watch Z is normal to the flank. Replay looks for
quiet, periodic motion primarily along Z and reports candidate cycles per minute,
not a validated breathing rate or exertion value. Whole-body sway along Z can
still look like breathing. Use uninterrupted quiet and post-activity recordings
with time-aligned, independently counted breaths to test agreement, missed
windows, and false candidates. Do not tag dorsal harness clips as flank data.
For each labeled recording, create a matching `<file-stem>.breaths.csv` with
header `timestamp_ns,event`, a `start` and `end` for the continuous span
reviewed on video, and one `breath` row per visible breath between them. Align
the video annotations to the sensor clock before replay. Compare baseline and
gyro-gated results on the same windows with complete gyro data. The experimental
gate withholds candidates above 0.12 rad/s rotation RMS; it does not identify a
loose mount or catch pure translation. Replay reports coverage and per-window
count error, not individual breath matching. Windows overlap, so do not sum
errors as if each physical breath were counted once.

`fixedFlankSimulationComparesAccuracyAndCoverage` runs the same eleven synthetic
windows through flank-only, gyro-gated, gyro-gated plus rhythm stability, and
adaptive recovery variants. Stability requires agreement across both halves of
the window. Adaptive recovery can also accept a steadily slowing rhythm across
three 10-second segments with usable flank motion and low rotation. It reports
the mean rate across the window, not the current rate. Compare count error and
coverage on breathing cases and false candidates on motion-only cases. Pure
translation can mimic either a stable or slowing breathing rhythm. These are
replay-only results. The burst-veto variant rejects adaptive candidates when
any rolling one-second gyro RMS exceeds 0.25 rad/s. Compare against adaptive
recovery alone: rejecting brief rotation also loses real breathing readings
overlapping the burst.

Set `HANDLER_REPLAY_DIR` to the input folder and run
`./gradlew :app:testDebugUnitTest --tests "*HarnessReplayTest" --rerun`
(`.\gradlew.bat` on Windows). Results are in the test report's standard output.
The report compares raw RMS, the live score, and two fixed replay-only scales
(linear with a 6 m/s² ceiling and logarithmic with a 12 m/s² ceiling). It shows
the share pinned at 100 and how many windows fall in the intersection of observed
walk and trot score ranges. These are descriptive statistics, not held-out accuracy.
The experimental sustained-motion score also smooths the logarithmic score over
time (30-second time constant) and discards its history when motion quality fails.
It is a recent movement summary, not a recovery, fatigue, or heat-stress measure.
Provisional false-warning starts per minute use only negative-label clips (all
except trot/run) long enough to show a warning, with duration including warm-up. Trot/run
is a motion label, not a medical warning ground truth. Report rejected samples and
gaps so missing coverage cannot look like improved accuracy. Compare dog and horse
separately, splitting by animal/session rather than by individual windows when
tuning smoothing or thresholds. No species presets until held-out field recordings
support them. Bench replay does not validate respiratory measurements.
