---
title: "Troubleshooting"
date: 2026-05-16T00:00:00Z
draft: false
weight: 90
toc: false
last_reviewed: "2026-05-16"
description: "Diagnoses for common pgJDBC failure modes, indexed by symptom and error message."
---

When the driver misbehaves, the issue is almost always one of a small
set of recurring patterns: a TLS configuration mismatch, a batched
operation that fills both ends of the TCP socket, a stale prepared
statement, or similar. Each page below is keyed by the user-visible
symptom (the error message or behavior) rather than by the internal
component at fault, so it should be searchable from a stack trace.

The pages cite source: exception strings as they appear in
`org.postgresql.util.PSQLException`, the connection-property table at
[Connection properties](/documentation/reference/connection-properties/),
and the inline rationale in the driver's bytecode. Anything that
sounds like generic advice ("try restarting", "check the network") is
out of scope; the goal is concrete, code-anchored guidance.
