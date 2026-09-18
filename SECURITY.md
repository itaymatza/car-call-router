# Security policy

## Supported source

Security fixes are considered for the current default branch. Historical releases, archived build artifacts, forks, and locally modified builds are not supported unless a maintainer explicitly states otherwise.

## Reporting a vulnerability

Please report suspected security, privacy, or safety-relevant vulnerabilities privately through [GitHub private vulnerability reporting](../../security/advisories/new). Do **not** open a public issue for a report that could expose users, devices, credentials, call data, or an exploitable weakness.

A useful report describes the affected commit, Android version, prerequisites, reproducible steps, expected behavior, actual behavior, and the potential impact. Do not include phone numbers, real Bluetooth addresses, pairing records, account data, credentials, unredacted logs, screenshots containing personal data, or recordings. If sensitive material is needed, state that it is available and wait for a maintainer to provide a secure channel.

GitHub recommends a repository security policy so maintainers and reporters have an explicit private path for vulnerability disclosure. [1]

## Scope and urgency

Report issues that could bypass call-safety checks, expose private data, disclose secrets, permit unauthorized routing requests, cause unsafe repeated requests, or weaken the bounded-session model. A potential emergency-call, active-call, or privacy-impacting defect should be described as high priority.

This repository is not a vehicle emergency service or a real-time safety-support channel. Do not use it to report an emergency or rely on it for immediate driving, call-audio, or medical assistance.

## Handling expectations

A maintainer should acknowledge a credible report, evaluate reproducibility and impact, coordinate a fix when appropriate, and publish a disclosure only after affected users have had a reasonable opportunity to update. Timelines depend on severity, reproducibility, and maintainer availability; no fixed service-level commitment is made by this policy.

## References

[1]: https://docs.github.com/en/code-security/getting-started/securing-your-repository "GitHub Docs: Securing your repository"
