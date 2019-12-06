---
layout: faqs
title: PostgreSQL JDBC FAQ
resource: ../media
nav: ../
---

# Frequently Asked Questions
***
* [1. New versioning scheme](#versioning)
 * [1.1. Why the versioning change from 9.4.xxxx to 42.x.x?](#version-change)
 * [1.2. Why the number 42?](#why-42)
 * [1.3. What is not the 42.0.0 release?](#42-is-not)
* [2. XA](#xa)
 * [2.1. Does the driver have XA support?](#xa-support)
 * [2.2. What is "transaction interleaving"?](#transaction-interleaving)
* [3. Problems](#problems)
 * [3.1. executeBatch hangs without error Possible solutions](#executeBatch-hangs-without-error)
 * [3.2. I upgraded from 7.x to 8.x.  Why did my application break?](#upgradeTo80)
 * [3.3. I upgraded from 9.x to 42.x.  Why did my application break?](#upgradeTo42)

***
<a name="versioning"></a>
## 1. New versioning policy

<a name="version-change"></a>
### 1.1. Why the versioning change from 9.4.xxxx to 42.x.x?

We have three issues we are trying to address here.

a) We do not want to be tied to the server release schedule.

Previously the version was based on the server release to declare some kind
of compatibility. This has not been the case since the first 9.4.xxxx release.
Each 9.4.xxxx release merely incremented the last 4 digits, this leads us to the second issue.

b) Avoid confusion as to which version of PgJDBC to use with which server version.

The naming scheme previously had 9.4 in it which led people to believe it
was only for server version 9.4, when in fact PgJDBC 9.4.xxxx supported all
versions of PostgreSQL 8.2 and higher.
The use of 9.4 in the PgJDBC version caused some users to ask when we would release
a version of PgJDBC for PostgreSQL 9.5 (or 9.6 or 10). And the 9.4 caused other
users to continue using outdated versions of PgJDBC like 8.4 build 703 because they
were connecting to PostgreSQL 8.4.

The driver is version agnostic for the most point so there is no reason to
tie it to a specific server version. Unless you have unusual requirements
(running old applications or JVMs), you should be using the latest driver version..

c) The previous version policy did not allow us to clearly differentiate between
bug fixes releases and feature releases.

The new version policy will allow us to use more or less
<a href="http://semver.org/">Semantic Versioning</a>, and have a more clear
understanding of the versions.

<a name="why-42"></a>
### 1.2. Why the number 42?

42 was more or less chosen at random. But it is large enough to avoid
any future conflicts with the server. Given current server project policies,
server version 42 should come out in 2049, plus or minus a bit.

Some say that "The answer to the ultimate question of life,
the universe and everything is 42."

<a name="42-is-not"></a>
### 1.3. What is the 42.0.0 release not?

This release is not a rewrite of the driver, is not using a new architecture,
and is not using something special. It is the continuation of the same driver
following a better versioning policy.

<a name="xa"></a>
## 2. XA

<a name="xa-support"></a>
### 2.1. Does the driver have XA support?

Yes, starting with the 8.1dev-403 driver. However, transaction interleaving is
not supported.

<a name="transaction-interleaving"></a>
### 2.2. What is "transaction interleaving"?

Transaction interleaving means that one database connection can be
used for multiple transactions at the same time, switching between
the transactions.

Transaction interleaving is mostly useless, but it's a required part
of the JTA specification. Some application servers use it to allow a
bit more concurrency without allocating a bigger jdbc connection
pool.

Few JDBC drivers support transaction interleaving properly. Some
fake it by issuing early prepare commands, risking transaction
integrity, some give strange error messages, some fail in other,
subtle ways. The PostgreSQL JDBC driver does it's best to detect
interleaving and throws a proper error message when it can't do
what's requested.

Because of the lack of driver support, all of the popular application
servers provide options to work around it, or don't use it at all.
Therefore, lack of transaction interleaving shouldn't affect your
application or data integrity.

See the JTA specification, section 3.4.4, or search the pgsql-jdbc
mailing list archives for more information.

***
<a name="problems"></a>
## 3. Problems

<a name="executeBatch-hangs-without-error"></a>
### 3.1. executeBatch hangs without error Possible solutions

This is related to batched queries and synchronous TCP.

The thing to look at is setting the network buffer
sizes to use to large values to avoid the deadlock.  The default values
are machine dependent which also explains it working or not on different
machines (credit to Kris Jurka)

<a name="upgradeTo80"></a>
### 3.2. I upgraded from 7.x to 8.x.  Why did my application break?

By default, 8.x versions of the driver use protocol version 3 when
communicating with servers 7.4 or higher.  This protocol allows for
more efficient query execution and enables true server-side
prepared statements, but also places some additional restrictions on
queries.  Problems with upgrading the driver generally fall into one
of two categories:

__Parameter Typing.__ Previous versions of the driver
sent all PreparedStatement parameters to the server as untyped
strings, and allowed the server to infer their types as appropriate.
When running protocol version 3 however, the driver specifies
the type of each parameter as it is being sent.  The upshot of this is
that code which was previously able to call (for example):
<span style="font-family: Courier New,Courier,monospace;">PreparedStatement.setObject(1, "5")</span>
to set an integer parameter now breaks, because setting a String value
for an integer parameter is not allowed. As an alternative, you can call
<span style="font-family: Courier New,Courier,monospace;">PreparedStatement.setObject(1, "5", java.sql.Types.OTHER)</span>
which explicitly tells the driver to send that String parameter as Oid.UNSPECIFIED.

__Parameter Position.__ Previous versions of the driver
emulated PreparedStatements by performing string replacements
each time the query was executed.  Newer drivers using protocol 3
however actually use server-side prepared statements with
placeholders for the positional parameters. The upshot of this is
that '?' positional parameters are now only allowed where the
PostgreSQL back-end allows parameters.

In situations where it is difficult to modify the Java code and/or
queries to work with the newer protocol version, it is possible to
force a pre-42.x version of the driver to use an older protocol version
to restore the old behavior. Look for the
<span style="font-family: Courier New,Courier,monospace;">protocolVersion</span>
connection parameter in the documentation for the PgJDBC driver version
that you are using.

<a name="upgradeTo42"></a>
### 3.3. I upgraded from 9.x to 42.x.  Why did my application break?

Support for the V2 frontend/backend protocol was removed in PgJDBC 42.0.0.
If you were using the
<span style="font-family: Courier New,Courier,monospace;">protocolVersion</span>
connection parameter, your connections will fail when you upgrade to 42.x.

