# 5. API keys stored as SHA-256, not bcrypt

**Status:** accepted

## Context
API keys authenticate every request, including high-rate event ingest. Passwords are hashed with
slow functions because people choose guessable ones; API keys are generated.

## Decision
Keys look like `hr_live_<8-char prefix>_<32 random base62 chars>` (about 190 bits of entropy). The
plaintext is shown once. The database stores the prefix (indexed lookup) and the SHA-256 of the
full key, compared in constant time. Revoked keys and keys of disabled applications are rejected.

## Consequences
- Authentication costs one indexed query and one hash, not 50–100 ms of bcrypt per request.
- A stolen database does not reveal usable keys: brute-forcing 190 bits is infeasible even with a fast hash.
- **Ceiling:** keys are only as strong as their randomness (`SecureRandom`).

## Alternatives
- bcrypt/argon2: right for passwords; for API keys it is mostly a self-inflicted latency cost.
- Opaque random tokens with server-side sessions: an extra lookup and state for no gain.
