# 7. SSRF protection checked at registration and again at send time

**Status:** accepted

## Context
Customers choose the URLs HookRelay calls from inside its network. Without a guard, an endpoint
could target `169.254.169.254` (cloud metadata), `localhost` admin ports or private services.
Checking only at registration is not enough: DNS can change afterwards (DNS rebinding).

## Decision
`SsrfGuard` requires https (http only when explicitly allowed for local/demo setups), resolves
the host, and rejects any address in a blocked range: loopback, RFC 1918, link-local,
carrier-grade NAT, multicast, reserved, IPv6 ULA/link-local, NAT64. It runs when an endpoint is
created or its URL changes, and **again before every send**. Redirects are never followed.
Explicit allow-ranges exist for development and the compose demo only.

## Consequences
- A rebinding attack fails at the send step: the attempt is recorded as `SSRF_BLOCKED`.
- Response bodies are capped (4 KiB stored) and time-boxed (connect 5s, total 15s).
- **Ceiling:** there is a small window between the check and the connection (the JDK HTTP client
  resolves again). Closing it fully requires connecting to the checked IP while sending the
  original Host and SNI, or an egress proxy, which is how a production deployment would enforce
  it at the network edge.

## Alternatives
- An egress proxy (e.g. Smokescreen) only: stronger, but another service for a single-node demo.
