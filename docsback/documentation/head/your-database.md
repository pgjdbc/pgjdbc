---
layout: default_docs
title: Creating a Database
header: Chapter 2. Setting up the JDBC Driver
resource: /documentation/head/media
previoustitle: Preparing the Database Server for JDBC
previous: prepare.html
nexttitle: Chapter 3. Initializing the Driver
next: use.html
---
		
When creating a database to be accessed via JDBC it is important to select an
appropriate encoding for your data. Many other client interfaces do not care
what data you send back and forth, and will allow you to do inappropriate things,
but Java makes sure that your data is correctly encoded.  Do not use a database
that uses the `SQL_ASCII` encoding. This is not a real encoding and you will
have problems the moment you store data in it that does not fit in the seven
bit ASCII character set. If you do not know what your encoding will be or are
otherwise unsure about what you will be storing the `UNICODE` encoding is a
reasonable default to use.
