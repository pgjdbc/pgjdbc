---
layout: default_docs
title: Using the ResultSet Interface
header: Chapter 5. Issuing a Query and Processing the Result
resource: media
previoustitle: Using the Statement or PreparedStatement Interface
previous: statement.html
nexttitle: Performing Updates
next: update.html
---

The following must be considered when using the `ResultSet` interface:

* Before reading any values, you must call `next()`. This returns true if there
	is a result, but more importantly, it prepares the row for processing.
* You must close a `ResultSet` by calling `close()` once you have finished using
	it.
* Once you make another query with the `Statement` used to create a `ResultSet`,
	the currently open `ResultSet` instance is closed automatically.