---
title: "Initializing the Driver (moved)"
description: "Legacy driver-initialization hub, now split into Quick start, JDBC URL syntax, Unix sockets, connection fail-over, and the connection-properties reference."
date: 2026-05-13T00:00:00Z
draft: false
weight: 2
toc: false
last_reviewed: "2026-05-13"
# Hidden from sidebar / section listings; the page still resolves at the
# old URL and serves as a hub + JS redirector for legacy deep links.
build:
  list: never
---

This page was split into focused topical pages. If you arrived here
from an external link, you're probably looking for one of these:

- **[Quick start](/documentation/install/)**: add the dependency and
  open your first connection.
- **[JDBC URL](/documentation/connect/url-syntax/)**: host/port,
  multi-host URLs, percent-encoding.
- **[Unix sockets](/documentation/connect/unix-sockets/)**: connecting
  via a filesystem path with `junixsocket`.
- **[Failover and load balancing](/documentation/connect/failover/)**:
  multi-host fail-over, `targetServerType`, host-state cache.
- **[Connection properties reference](/documentation/reference/connection-properties/)**:
  the complete table of every property the driver recognizes, with
  defaults, types, and the version each was introduced.

For deep links that pointed at a specific property anchor
(e.g. `/documentation/use/#sslmode`), this page automatically redirects
to the corresponding row on the reference page.

{{< legacy-anchors hub="use" >}}
