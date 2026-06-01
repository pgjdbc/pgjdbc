---
title: "Preparing the Database Server for JDBC"
---


Out of the box, Java does not support unix sockets so the PostgreSQL® server must be configured to allow TCP/IP connections. Starting with server version 8.0 TCP/IP connections are allowed from `localhost` . To allow connections to other interfaces
than the loopback interface, you must modify the `postgresql.conf` file's `listen_addresses` setting.

Once you have made sure the server is correctly listening for TCP/IP connections the next step is to verify that users are allowed to connect to the server. Client authentication is setup in `pg_hba.conf` . Refer to the main PostgreSQL® [documentation](https://www.postgresql.org/docs/current/auth-pg-hba-conf.html) for details .

## Creating a Database

When creating a database to be accessed via JDBC it is important to select an appropriate encoding for your data. Many other client interfaces do not care what data you send back and forth, and will allow you to do inappropriate things, but Java makes sure that your data is correctly encoded.  Do not use a database that uses the `SQL_ASCII` encoding. This is not a real encoding and you will have problems the moment you store data in it that does not fit in the seven bit ASCII character set. If you do not know what your encoding will be or are otherwise unsure about what you will be storing the `UNICODE` encoding is a reasonable default to use.
