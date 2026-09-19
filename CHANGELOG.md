# Changelog

## 0.3.0-beta.1

- Preserve the verified API 34+ Telecom endpoint routing proof of concept.
- Distinguish temporary unknown projection/HFP evidence from confirmed disconnection.
- Treat transient Telecom endpoint-list gaps as a safe pause instead of a permanent false failure.
- Prevent stale Bluetooth endpoints from being misclassified as a user-selected alternative route.
- Add regression coverage for observer uncertainty, endpoint churn, and recovery.
- Run the production-service callback harness as part of Gradle CI.
- Record the owner-confirmed real-device POC and define explicit production-release gates.
