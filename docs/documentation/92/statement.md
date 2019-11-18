---
layout: default_docs
title: Using the Statement or PreparedStatement Interface
header: Chapter 5. Issuing a Query and Processing the Result
resource: media
previoustitle: Chapter 5. Issuing a Query and Processing the Result
previous: query.html
nexttitle: Using the ResultSet Interface
next: resultset.html
---

The following must be considered when using the `Statement` or `PreparedStatement`
interface:

* You can use a single `Statement` instance as many times as you want. You could
	create one as soon as you open the connection and use it for the connection's
	lifetime. But you have to remember that only one `ResultSet` can exist
	per `Statement` or `PreparedStatement` at a given time.
* If you need to perform a query while processing a `ResultSet`, you can simply
	create and use another `Statement`.
* If you are using threads, and several are using the database, you must use a
	separate `Statement` for each thread. Refer to [Chapter 10, *Using the Driver in a Multithreaded or a Servlet Environment*](thread.html)
	if you are thinking of using threads, as it covers some important points.
* When you are done using the `Statement` or `PreparedStatement` you should close
	it.