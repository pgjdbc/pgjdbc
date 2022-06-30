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
* When PreparedStatement API is used, `ResultSet` switches to binary mode after 
	five query executions (this default is set by the `prepareThreshold` 
	connection property, see [Server Prepared Statements](server-prepare.md)). 
	This may cause unexpected behavior when some methods are called. For example, 
	results on method calls such as `getString()` on non-string data types, 	
	while logically equivalent, may be formatted differently after execution exceeds 
	the set `prepareThreshold` when conversion to object method switches to one 
	matching the return mode. 