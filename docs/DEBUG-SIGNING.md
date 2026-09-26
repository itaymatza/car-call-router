# Persistent debug APK signing

The published APKs use one protected `debug-signing` keystore. Ordinary PR/CI artifacts
remain disposable and cannot update a published installation.

The repository owner runs `bash tools/configure-debug-signing.sh` **once** from a trusted
Mac or Linux machine with GitHub CLI authentication, Java `keytool`, and OpenSSL.
The script creates an encrypted PKCS12 key in `~/.config/car-call-router` (or
`$XDG_CONFIG_HOME/car-call-router`), stores the same key and passwords as GitHub
environment secrets, and pins its certificate SHA-256 as an environment variable.
Back up the local directory in a secure, private location. Re-running the script
uses the existing key; if only one backup file remains, it stops instead of rotating
the signing identity.

The publication job runs only for pushes to `main`. It refuses to publish if any
secret or the pinned certificate is absent, signs the debug APK with this key,
checks its certificate and package/version identity, then deletes its temporary
runner copy of the keystore. A certificate mismatch fails before publication.

The first protected-key APK cannot update beta.11 or the initial beta.12 release:
those builds were signed by disposable GitHub runner keys. Uninstall the older
version once, then install the protected-key release and redo its configuration
and one-time authorization. Future published APKs signed by the same backed-up
key can install in place if their version code increases. Keep the protected
non-debug release key separate from this debug/testing key.
