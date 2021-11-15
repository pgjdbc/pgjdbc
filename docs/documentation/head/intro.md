---
layout: default_docs
title: Chapter 1. Introduction
header: Chapter 1. Introduction
resource: /documentation/head/media
previoustitle: The PostgreSQL™ JDBC Interface
previous: index.html
nexttitle: Chapter 2. Setting up the JDBC Driver
next: setup.html
---
		
Java Database Connectivity (JDBC) is an application programming interface (API) for
the programming language Java, which defines how a client may access a database.
It is part of the Java Standard Edition platform and provides methods to query and
update data in a database, and is oriented towards relational databases.
		
PostgreSQL JDBC Driver (PgJDBC for short) allows Java programs to connect to a PostgreSQL
database using standard, database independent Java code. Is an open source JDBC driver
written in Pure Java (Type 4), and communicates in the PostgreSQL native network protocol.
Because of this, the driver is platform independent; once compiled, the driver
can be used on any system.

The current version of the driver should be compatible with PostgreSQL 8.2 and higher
using the version 3.0 of the PostgreSQL protocol, and it's compatible with Java 8 (JDBC 4.2) and above.
  		
This manual is not intended as a complete guide to JDBC programming, but should
help to get you started. For more information refer to the standard JDBC API
documentation. Also, take a look at the examples included with the source.
