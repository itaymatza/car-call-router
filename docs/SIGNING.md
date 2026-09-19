# Stable beta signing

GitHub's ordinary debug keystore changes between hosted runners. Those APKs are suitable for CI
validation but not for repeated installation on the qualified phone: Android will reject an update
signed by a different certificate, and uninstalling clears local configuration and normally
requires the protected AppOps authorization again.

The `Build signed beta APK` workflow uses a dedicated `beta-signing` GitHub environment. It refuses
to build unless all four environment secrets are present:

| Secret | Value |
|---|---|
| `BETA_KEYSTORE_BASE64` | Base64 encoding of the complete JKS/PKCS12 keystore file |
| `BETA_KEYSTORE_PASSWORD` | Keystore password |
| `BETA_KEY_ALIAS` | Alias of the signing key |
| `BETA_KEY_PASSWORD` | Private-key password |

Create the signing key once on a trusted offline machine. Keep an encrypted backup outside GitHub;
losing the key makes future in-place updates impossible. Never commit the keystore, its Base64
encoding, passwords, certificate export, or command output containing secrets.

Configure the four secrets under **Settings → Environments → beta-signing**. Restrict deployments
to the protected branch and, if appropriate, require reviewer approval. Run the workflow manually
for beta builds or push an annotated `v*` tag for a GitHub release. The workflow emits:

- the non-debuggable signed APK;
- an APK and certificate verification record;
- an APK SHA-256 file;
- a GitHub artifact provenance attestation.

## One-time migration and upgrade proof

An APK signed by this new key cannot update a build signed by an old GitHub debug key. For the first
stable-key installation only:

1. Export any needed redacted diagnostics and note the selected device settings.
2. Uninstall the old debug-signed package.
3. Install the first signed-beta APK, configure it, and grant `MANAGE_ONGOING_CALLS` through ADB.
4. Record the certificate SHA-256 and keep this APK as the upgrade baseline.
5. Build a later version with the same workflow and install it with `adb install -r`.
6. Confirm installation succeeds without uninstalling, settings remain present, and
   `cmd appops get --uid org.carcallrouter.companion MANAGE_ONGOING_CALLS` still reports `allow`.

Only the successful upgrade test establishes continuity for the intended Samsung build. The source
configuration alone cannot guarantee how a future OS update or explicit AppOps reset will behave.
