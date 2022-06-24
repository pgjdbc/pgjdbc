---
title: Creating a Database
date: 2022-06-19T22:46:55+05:30
draft: false
menu:
  docs:
    parent: "chapter2"
    weight: 4
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
