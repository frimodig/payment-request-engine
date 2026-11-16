# Threat model

A first threat model for the engine in [architecture.md](architecture.md), using STRIDE per trust boundary. It names the threats that matter most for a payment engine and the control for each. It is a starting point for a session with security architecture, not a finished assessment.

## What we protect

- **Money movement.** An instruction makes a downstream move money. The worst outcomes are a payment nobody authorised, a second payment, or money sent to the wrong account.
- **The truth about each payment.** Partners act on its status (they ship goods on `PAID`), and the bank answers for it.
- **Personal data.** Debtor and creditor identification and account details.
- **The audit trail.** It proves what was asked, what was done and who did it.

## Trust boundaries

| # | Boundary | Who is on the other side |
|---|---|---|
| B1 | Partner to front doors (API, file channel) | External partners, or anyone holding a partner's credentials |
| B2 | Downstream calls and events (sends, status queries, webhooks, return files) | Downstream payment systems, or anyone who can reach the webhook endpoint |
| B3 | Engine to partner webhooks | URLs partners register |
| B4 | Operators and admins (manual actions, configuration, break-glass) | Bank staff |
| B5 | Engine to its database and object storage | The platform |
| B6 | Build and deployment | Dependencies, CI, the container registry |

## Threats and controls

Status: **built** means the proof of concept enforces it with a test; **designed** means the recommendation includes it but it is not built; **gap** means neither yet.

| Threat (STRIDE) | Example | Controls | Status |
|---|---|---|---|
| Spoofing, B1 | Stolen partner credentials submit requests that route money to an account the attacker controls | mTLS or OAuth client credentials with keys in KMS/HSM; keys scoped to submit or cancel; creditor accounts on an allowlist verified at partner onboarding; per-partner usage profile and trend signals; runtime pause per partner | Designed; the creditor allowlist is a gap |
| Spoofing, B2 | A forged webhook reports `PAID` for an unpaid request | Authenticated webhooks (mTLS or signed payloads); events match only on provider and stored reference; the state graph rejects impossible events; `SETTLED` comes from reconciliation against bank statements, so a forged event can never settle a payment | Matching and graph built; authentication and settlement only from reconciliation designed (the PoC accepts `SETTLED` as a downstream event) |
| Tampering, B1 | A file is altered in transit or after receipt | Signed or encrypted file channel; hash recorded at receipt; received files in write-once storage for the retention period | Designed |
| Tampering, B5 | Someone with the application's database access edits an amount, a creditor or history | Updates allowed only on lifecycle, lease and downstream columns; instruction columns immutable; append-only grants on `payment_event`; a unique event sequence per payment; a state change refused without its event; a hash chain per payment anchored daily in the security log; a daily consistency job | Designed. The PoC enforces none of these: its event sequence is not unique and the application role can update any column |
| Spoofing, B3 | Someone forges a `PAID` webhook to a partner, or replays an old one, and goods ship | Signed deliveries (keys in KMS, rotated with overlap) covering body, event id and timestamp; partners confirm high-value payments through the status API | Designed |
| Tampering, B2 | A downstream reply carries a malicious reason code or reference | Reason codes stripped of control characters and bounded; references validated before storing; downstream data never logged | Built |
| Repudiation, B1 | A partner denies sending an instruction | Received file, its hash and the authenticated identity kept with the submission; API requests stored as received | Partly designed: raw API requests are a gap |
| Repudiation, B4 | An operator denies a manual action | Maker-checker with both names and a reason, enforced by schema constraints; break-glass logged | Constraints in the schema, not tested; manual actions not built |
| Information disclosure, B1 | A partner reads another partner's statuses by guessing ids | Every query scoped to the authenticated partner; random UUIDs as ids | Designed |
| Information disclosure, B5 | Personal data leaks through logs or event payloads | Allowlist of loggable fields; event payloads hold references, never personal data; encryption at rest; retention rules per data class | The PoC follows the payload rule; the rest designed |
| Information disclosure, B3 | A partner registers a webhook URL that points inside the bank (SSRF) | Only https to a fully qualified host name, never an IP literal, with no credentials in the URL; every address the host resolves to must be public (loopback, private, link-local including cloud metadata, carrier-grade NAT and other special-purpose ranges are blocked); checked at registration and before each delivery; delivery connects to the checked addresses and follows no redirects; the partner is told only that a URL is refused, never why, so registration cannot map internal names; an egress proxy that blocks internal ranges as a second layer; status payloads carry only the partner's own data | URL and address check built (`WebhookTarget`); connecting to the checked addresses, no redirects and the egress proxy designed with delivery; ports are not limited, a gap |
| Denial of service, B1 | Huge files, very long fields or floods block ingestion | Size and field limits at the door; per-partner rate and in-flight limits; staged, chunked file ingestion; separate claim lane for API traffic | Designed; the PoC lacks field limits and lanes (see [poc-limits.md](poc-limits.md)) |
| Denial of service, B2 | A downstream outage floods the manual queue | Backoff up to an outage horizon; `UNKNOWN` only after it; circuit breaker next | Backoff built |
| Elevation of privilege, B1 | A key scoped to submit is used to cancel or read other data | Scopes checked per endpoint | Designed |
| Elevation of privilege, B4 | Two operators, or one with two accounts, settle payments by hand, or a bulk action sweeps in payments added after approval | A defined set of manual actions with required evidence; `SETTLED` never set by hand without a statement entry; identity-provider subject ids; bulk lists frozen when requested; an extra approver above a value threshold | Designed |
| Information disclosure, B1 | A file with external XML entities reads internal hosts or files, or a nested-entity file exhausts the parser | DTDs and external entities off; pinned schema; streaming with depth and element limits; rejection reasons never echo partner values | Designed |
| Information disclosure, B1 | A rejection reason tells a partner that the bank suspects money laundering | One generic reason code for compliance rejections, signed off by compliance | Designed |
| Information disclosure, B5 | A database error writes debtor account data into logs | Terse database errors; the driver does not copy server detail; database logs, backups and WAL archives treated as personal data | Designed |
| Elevation of privilege, B4 | An operator moves a payment's state directly | No direct writes: manual actions are inputs applied through the state machine under a lease, after approval by a second person | Designed |
| Elevation of privilege, B6 | A compromised dependency or build step ships malicious code | Pinned dependencies with verified checksums; signed artifacts and SBOM; pull request approval as maker-checker for changes | Gradle wrapper checksum pinned (Gradle enforces it); the rest designed |

## Rules that follow

- **Sanctions screening fails closed.** No instruction is sent without a screening result. There is no switch to fail open.
- **A payment's state changes only through the state machine,** whoever asks: partner, downstream or operator. Every external input is stored first and applied by the lease holder.
- **Nothing a partner or downstream sends is trusted as a value to route money.** Creditor accounts come from onboarding, and settlement comes from the bank's own statements.

## Open questions for security architecture

- How partners authenticate on each channel, and how keys rotate.
- How files are signed and encrypted, and whether the bank's existing host-to-host channel already does it.
- Who may change partner profiles and routing rules, and whether those changes need maker-checker.
- The data classification of each table and file, which drives encryption and retention.
