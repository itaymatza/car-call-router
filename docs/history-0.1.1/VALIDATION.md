# Current status

See [AUDIT-0.1.1.md](AUDIT-0.1.1.md). No APK has been produced.

## Original validation plan (historical)

# Validation status — 2026-09-17

## Executed here

- Pure Kotlin routing and safety code compiled with `kotlinc-jvm 1.9.0`, running on OpenJDK 21.
- All **47** executable policy/safety checks passed. `core-test-results.txt` is the actual output.
- The tests cover target selection gates, genuine-active input, projection unknown/disconnected,
  unavailable BMW, verification vs submission, request serialization and timing, bounded retries,
  user overrides, pause, disconnect, call waiting, held calls, emergency classification,
  private/unavailable handles, conferences and a manual single-shot mode.
- XML parse, shell syntax, Python syntax and source-package integrity were checked separately.

## NOT executed / NOT established

- Android SDK compilation, Android lint or Gradle/JUnit execution.
- APK packaging, signing, installation, emulator instrumentation or UI rendering.
- Samsung Telecom admission with MANAGE_ONGOING_CALLS AppOps.
- Successful BMW route selection, microphone quality, Android Auto coexistence or 1–2 second timing.
- Projection provider availability on this head unit.
- Locked-screen, overnight, process-reclamation, permission-hibernation or reboot behavior.

The environment has Java/Kotlin but no Android SDK/Gradle distribution, and network downloads
failed. No APK is included. There are no invented build logs or device-test results. The
installer requires build, JUnit and lint success before installing; any failure stops it.

## Parked-device acceptance sequence

| Test | Expected evidence | Result |
|---|---|---|
| Initial setup | Runtime permissions and Telecom authorization true | Not run |
| Fresh non-emergency call with auto OFF | TELECOM_BOUND and call-state events | Not run |
| One-shot on bad route | ROUTE_REQUEST then exact BMW Telecom address; HFP SCO active | Not run |
| Far-end microphone + near-end audio | Both verified by two participants; not just an API callback | Not run |
| Android Auto coexistence | Projection stays true; navigation/display still operate | Not run |
| Call ends | Normal music/navigation resumes, without app resetting media | Not run |
| Incoming auto call | Request starts on ACTIVE, BMW observed within measured target interval | Not run |
| Outgoing auto call | No request during DIALING; request on ACTIVE | Not run |
| Known head-unit startup race | Only configured address reasserted; at most 3 requests in 4 seconds | Not run |
| Speaker/handset/Buds selected | Enforcement stops; no recurring requests | Not run |
| Pause/master OFF | No new request; pending request may complete | Not run |
| BMW/projection disconnect | Pending retries canceled; no profile reconnect attempts | Not run |
| Reboot/overnight | Fresh call binds service and authorization persists | Not run |
| Process restart mid-call | Already-active session remains passive | Not run |
| Second SIM | Correct SIM capability classification, same target BMW | Not run |
| Call waiting/conference | Automatic controller suspends; Android manages audio | Not run |
| Anonymous/unclassified call | Fail-safe skip with explicit log reason | Not run |

Do not place actual emergency calls to test. Emergency classification guards have offline
unit cases; real emergency service behavior remains the system's responsibility.

The optional competing-device setting is deliberately not guessed. First prove one successful
switch. Then use the measured logs to decide whether startup reassertion is necessary.
