---
title: "System Properties"
date: 2022-06-19T22:46:55+05:30
draft: false
weight: 50
toc: false
---

`pgjdbc.config.cleanup.thread.ttl` (milliseconds, default: 30000). The driver has an internal cleanup thread which monitors and cleans up unclosed connections. This property sets the duration the cleanup thread will keep running if there is nothing to clean up.
