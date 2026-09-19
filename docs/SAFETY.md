# Safety and privacy boundaries

## Eligibility checks

The service makes no route request unless it can establish all of the following: protected Telecom authorization, required runtime permissions, exactly one live active call, a SIM-backed phone account, a non-emergency telephone number classification, a configured target uniquely resolved to a current Telecom Bluetooth endpoint, and a connected target in the HFP profile. Automatic mode additionally requires the master toggle and a verified projection-host state.

Calls are rejected when they are emergency or emergency-callback calls, external or self-managed calls, conferences, non-SIM calls, calls with a hidden or non-telephone handle, calls with unavailable emergency classification, or sessions containing multiple calls. The app does not replace the default dialer, manage media profiles, modify A2DP, disconnect Bluetooth, record audio, read contacts, read call logs, or send network telemetry.

## Bounded behavior

A fresh automatic session has a four-second startup window, waits at least 300 milliseconds between attempts, and has a maximum budget of three route requests. Once the target route is observed, only a specifically configured competing route can cause a bounded reassertion. Speaker, handset, wired, streaming, unknown Bluetooth, or other route changes are treated as possible user choices and are not fought. Routing errors stop the session without blind retry.

Safety-relevant callback edges are latched before deferred evaluation. Therefore, projection loss, target loss, a call hold, a second call, a conference child, a settings change during the session, service teardown, or user pause cancels further automatic requests for that session.

## Authorization safety

Run the ADB AppOps command only for the exact installed application ID. Verify that Android reports `MANAGE_ONGOING_CALLS: allow`, then disable USB debugging and Wireless debugging when setup is complete. Do not leave an unknown computer authorized for debugging. The grant can be revoked as documented in [AUTHORIZATION.md](AUTHORIZATION.md).

A generic Bluetooth or Companion Device Manager association is not equivalent to this authorization, and the app must not report it as successful Telecom access.

## Public-source hygiene

Bluetooth addresses and device names can be personal data in context. Keep real settings local; do not commit exported settings, diagnostic logs, screenshots, issue attachments, or test output that identifies people or hardware. Do not commit signing keys, certificates, credentials, or APKs. Generated content and common sensitive file types are ignored, but review staged changes before every push.
