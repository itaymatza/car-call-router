# Direct-download debug beta

This pre-release makes the current Car Call Router beta installable as a direct APK download.

## Install

1. Download the `.apk` attached to this release.
2. Open the APK on an Android 14+ phone and allow the browser or file manager to install unknown
   apps when prompted.
3. Open Car Call Router and complete the guided permissions, target-device selection, and
   one-time ADB authorization.
4. Test the manual one-shot route while safely parked before enabling automatic routing.

## Important beta limitations

- This is a debug-signed testing build, not a production-ready release.
- Its signing certificate is pinned and recorded in **apk-verification.txt**. Builds published after
  the protected debug signing setup use the same key and can update each other in place. Older
  beta.11 and beta.12 builds used temporary runner keys: the first protected-key build cannot
  replace them. Uninstall the older app once, then reinstall, reconfigure, and repeat the one-time
  authorization. The separately protected non-debug release certificate also differs.
- Real-car stability qualification is still incomplete. Never use emergency calls for testing and
  do not interact with the app while driving.

The attached `.sha256` file verifies the APK bytes. The GitHub artifact attestation links the APK
to the workflow and source commit that produced it.

## What changed in beta.12

- Call-start routing now uses a short quiet period that extends when Telecom reports another
  endpoint request or route change, capped at 900 ms after the call becomes active. With no
  competing activity, the BMW request can start after 300 ms instead of a fixed 500 ms.
- HFP audio is sampled every 250 ms during verification. Telecom's callback wait is reduced to
  1.5 seconds while the full four-second audio evidence window remains available for slow SCO.
- If another service requests an endpoint after the BMW request, the router still observes BMW
  audio but will not issue a selector recovery request that could fight that service.
- The latest source build re-queries HFP audio during the bounded verification window, including
  at its deadline, so a missing Bluetooth broadcast cannot make a stale audio reading the final
  verdict. Diagnostics record the sample age, trigger, and redacted audio-device alias.
- Technical status now separates Telecom's displayed endpoint from observed HFP audio and marks
  BMW confirmed only when the target's audio has passed the stability check.
- Replaces callback-fighting behavior with one bounded BMW request verified by exact, stable
  HFP/SCO ownership rather than the endpoint displayed by the Phone UI.
- Restores Samsung's manual BMW selector once when Telecom displays BMW but the configured Android
  Auto endpoint still owns call audio, without retrying BMW or guessing an endpoint.
- Adds complete privacy-conscious routing evidence, selector-recovery diagnostics, and capture
  analysis so the next parked run can distinguish Telecom display from actual audio ownership.
- Stops selector recovery when speaker, handset, wired, or another Bluetooth route appears and
  adds deterministic, randomized, JDK 17/21, coverage, and merge-queue regressions.
- Includes the selector-recovery hardening merged on September 26, 2026. Remains a debug beta pending parked real-car qualification.
