# Configuration guidance

## Build identity

The Android Gradle module accepts three optional Gradle properties. They may be supplied through a local Gradle properties file outside the repository or as `-P` arguments in a build command.

| Property | Default | Purpose |
|---|---|---|
| `APP_APPLICATION_ID` | `org.carcallrouter.companion` | Installable Android application ID. Choose an ID controlled by the build owner. |
| `APP_VERSION_CODE` | `1` | Monotonically increasing Android version code. |
| `APP_VERSION_NAME` | `0.1.0` | Human-readable version string. |

Example:

```sh
bash gradlew \
  -PAPP_APPLICATION_ID=example.callroute \
  -PAPP_VERSION_CODE=3 \
  -PAPP_VERSION_NAME=0.3.0 \
  :app:assembleDebug
```

Changing the application ID creates a distinct Android application. Any protected authorization must therefore be performed for the exact application ID installed on the device. The generic source namespace is intentionally fixed; changing it requires an explicit source-package migration, not a Gradle property.

## Runtime choices

At runtime, a person configuring a test device chooses the target Bluetooth call device from already paired devices. The app stores the chosen address and display name in application-private `SharedPreferences`; neither value is compiled into the source tree or transmitted by the app. An optional competing device can be selected to permit bounded reassertion only against that explicit route. When no competing device is set, the automatic path makes a single initial request instead of contesting a later route change.

The master automation toggle is disabled by default. It cannot be enabled until the target is selected, the required runtime permissions are granted, and the operating system reports that the protected ongoing-call authorization is present. A manual one-shot is available only while Telecom has bound the service, and it retains all authorization, call-safety, and target-identity requirements.

## Projection integration

Automatic mode uses the AndroidX car-app host-provider protocol as an optional projection-state gate. The monitor deliberately avoids inferring projection state from Bluetooth names, Wi-Fi networks, package process state, or broadcast extras. A missing, unknown, or lost projection state prevents new automatic route requests. The manual one-shot has an explicit projection-gate bypass for parked testing only.
