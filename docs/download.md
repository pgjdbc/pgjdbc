---
layout: downloads
title: PostgreSQL JDBC Download
resource: /media
nav:
---

# Download
***
* [About](#about)
* [Current Version](#current)
* [Other Versions](#others)
* [Archived Versions](#archived)

***
<a name="about"></a>
## About

Binary JAR file downloads of the JDBC driver are available here
and the current version with [Maven Repository](http://search.maven.org/#search%7Cga%7C1%7Cg%3A%22org.postgresql%22%20AND%20a%3A%22postgresql%22).
Because Java is platform neutral, it is a simple process of just
downloading the appropriate JAR file and dropping it into your
classpath.  Source versions are also available here for recent
driver versions.

{% for post in site.categories.new_release limit:1 %}
{% capture current_version %}{{ post.version }}{% endcapture %}
{% endfor %}

***
<a name="current"></a>
## Current Version *{{ current_version }}*

This is the current version of the driver.  Unless you have unusual
requirements (running old applications or JVMs), this is the driver
you should be using.  It supports PostgreSQL 8.2 or newer and
requires Java 6 or newer.  It contains support for SSL and the
javax.sql package.

* If you are using Java 8 or newer then you should use the JDBC 4.2 version.
* If you are using Java 7 then you should use the JDBC 4.1 version.
* If you are using Java 6 then you should use the JDBC 4.0 version.
* If you are using a Java version older than 6 then
you will need to use a JDBC3 version of the driver, which will by
necessity not be current, found in [Other Versions](#others).

[PostgreSQL JDBC 4.2 Driver, {{ current_version }}](/download/postgresql-{{ current_version }}.jar)

[PostgreSQL JDBC 4.1 Driver, {{ current_version }}.jre7](/download/postgresql-{{ current_version }}.jre7.jar)

[PostgreSQL JDBC 4.0 Driver, {{ current_version }}.jre6](/download/postgresql-{{ current_version }}.jre6.jar)


***
<a name="others"></a>
## Other Versions

Many other versions of the JDBC driver are available.  This includes
development versions, compatibility with older JDKs, and previous
versions of the driver.

To determine JDK/JVM compatibility this following list matches up
versions of the JVM with the JDBC specification implemented.

* JDK 1.1 - JDBC 1.  Note that with the 8.0
	release JDBC 1 support has been removed, so look to update your
	JDK when you update your server.
* JDK 1.2, 1.3 - JDBC 2.
* JDK 1.3 + J2EE - JDBC 2 EE.  This contains additional support
	for javax.sql classes.
* JDK 1.4, 1.5 - JDBC 3.  This contains support
	for SSL and javax.sql, but does not require J2EE as it has been
	added to the J2SE release.
* JDK 6 - JDBC 4.0  Support for JDBC4 methods is not complete,
	but the majority of methods are implemented.
* JDK 7 - JDBC 4.1  Support for JDBC4 methods is not complete,
	but the majority of methods are implemented.
* JDK 8 - JDBC 4.2  Support for JDBC4 methods is not complete,
	but the majority of methods are implemented.

<br/>
<div class="tblBasic">
<table class="tblBasicGrey" style="width: 100%;" summary="Other Versions" border="1" cellspacing="0" cellpadding="0">
<tr>
	<th>Version</th>
	<th>JDBC 4.0</th>
	<th>JDBC 4.1</th>
	<th>JDBC 4.2</th>
	<th>Source</th>
</tr>

{% for post in site.categories.new_release %}
{% capture ver %}{{ post.version }}{% endcapture %}
<tr>
	<td>{{ ver }}</td>
	<td><a href="/download/postgresql-{{ ver }}.jre6.jar">{{ ver }} JDBC 4</a></td>
	<td><a href="/download/postgresql-{{ ver }}.jre7.jar">{{ ver }} JDBC 41</a></td>
	<td><a href="/download/postgresql-{{ ver }}.jar">{{ ver }} JDBC 42</a></td>
	<td><a href="/download/postgresql-jdbc-{{ ver }}.src.tar.gz">{{ ver }} JDBC Source</a></td>
</tr>
{% endfor %}

<tr>
	<td>9.4.1212</td>
	<td><a href="/download/postgresql-9.4.1212.jre6.jar">9.4.1212 JDBC 4</a></td>
	<td><a href="/download/postgresql-9.4.1212.jre7.jar">9.4.1212 JDBC 41</a></td>
	<td><a href="/download/postgresql-9.4.1212.jar">9.4.1212 JDBC 42</a></td>
	<td><a href="/download/postgresql-jdbc-9.4.1212.src.tar.gz">9.4.1212 JDBC Source</a></td>
</tr>

<tr>
	<td>9.4.1211</td>
	<td><a href="/download/postgresql-9.4.1211.jre6.jar">9.4.1211 JDBC 4</a></td>
	<td><a href="/download/postgresql-9.4.1211.jre7.jar">9.4.1211 JDBC 41</a></td>
	<td><a href="/download/postgresql-9.4.1211.jar">9.4.1211 JDBC 42</a></td>
	<td><a href="/download/postgresql-jdbc-9.4.1211.src.tar.gz">9.4.1211 JDBC Source</a></td>
</tr>

<tr>
	<td>9.4.1210</td>
	<td><a href="/download/postgresql-9.4.1210.jre6.jar">9.4.1210 JDBC 4</a></td>
	<td><a href="/download/postgresql-9.4.1210.jre7.jar">9.4.1210 JDBC 41</a></td>
	<td><a href="/download/postgresql-9.4.1210.jar">9.4.1210 JDBC 42</a></td>
	<td><a href="/download/postgresql-jdbc-9.4.1210.src.tar.gz">9.4.1210 JDBC Source</a></td>
</tr>

<tr>
	<td>9.4.1209</td>
	<td><a href="/download/postgresql-9.4.1209.jre6.jar">9.4.1209 JDBC 4</a></td>
	<td><a href="/download/postgresql-9.4.1209.jre7.jar">9.4.1209 JDBC 41</a></td>
	<td><a href="/download/postgresql-9.4.1209.jar">9.4.1209 JDBC 42</a></td>
	<td><a href="/download/postgresql-jdbc-9.4.1209.src.tar.gz">9.4.1209 JDBC Source</a></td>
</tr>

<tr>
	<td>9.4.1208</td>
	<td><a href="/download/postgresql-9.4.1208.jre6.jar">9.4.1208 JDBC 4</a></td>
	<td><a href="/download/postgresql-9.4.1208.jre7.jar">9.4.1208 JDBC 41</a></td>
	<td><a href="/download/postgresql-9.4.1208.jar">9.4.1208 JDBC 42</a></td>
	<td><a href="/download/postgresql-jdbc-9.4-1208.src.tar.gz">9.4.1208 JDBC Source</a></td>
</tr>

<tr>
	<td>9.4.1207</td>
	<td><a href="/download/postgresql-9.4.1207.jre6.jar">9.4.1207 JDBC 4</a></td>
	<td><a href="/download/postgresql-9.4.1207.jre7.jar">9.4.1207 JDBC 41</a></td>
	<td><a href="/download/postgresql-9.4.1207.jar">9.4.1207 JDBC 42</a></td>
	<td><a href="/download/postgresql-jdbc-9.4-1207.src.tar.gz">9.4.1207 JDBC Source</a></td>
</tr>
<tr>
	<td>9.4 Build 1206</td>
	<td><a href="/download/postgresql-9.4-1206-jdbc4.jar">9.4-1206 JDBC 4</a></td>
	<td><a href="/download/postgresql-9.4-1206-jdbc41.jar">9.4-1206 JDBC 41</a></td>
	<td><a href="/download/postgresql-9.4-1206-jdbc42.jar">9.4-1206 JDBC 42</a></td>
	<td><a href="/download/postgresql-jdbc-9.4-1206.src.tar.gz">9.4-1206 JDBC Source</a></td>
</tr>
<tr>
	<td>9.4 Build 1205</td>
	<td><a href="/download/postgresql-9.4-1205.jdbc4.jar">9.4-1205 JDBC 4</a></td>
	<td><a href="/download/postgresql-9.4-1205.jdbc41.jar">9.4-1205 JDBC 41</a></td>
	<td><a href="/download/postgresql-9.4-1205.jdbc42.jar">9.4-1205 JDBC 42</a></td>
	<td><a href="/download/postgresql-jdbc-9.4-1205.src.tar.gz">9.4-1205 JDBC Source</a></td>
</tr>
<tr>
	<td>9.4 Build 1204</td>
	<td><a href="/download/postgresql-9.4-1204.jdbc4.jar">9.4-1204 JDBC 4</a></td>
	<td><a href="/download/postgresql-9.4-1204.jdbc41.jar">9.4-1204 JDBC 41</a></td>
	<td><a href="/download/postgresql-9.4-1204.jdbc42.jar">9.4-1204 JDBC 42</a></td>
	<td><a href="/download/postgresql-jdbc-9.4-1204.src.tar.gz">9.4-1204 JDBC Source</a></td>
</tr>
<tr>
	<td>9.4 Build 1203</td>
	<td><a href="/download/postgresql-9.4-1203.jdbc4.jar">9.4-1203 JDBC 4</a></td>
	<td><a href="/download/postgresql-9.4-1203.jdbc41.jar">9.4-1203 JDBC 41</a></td>
	<td><a href="/download/postgresql-9.4-1203.jdbc42.jar">9.4-1203 JDBC 42</a></td>
	<td><a href="/download/postgresql-jdbc-9.4-1203.src.tar.gz">9.4-1203 JDBC Source</a></td>
</tr>
<tr>
	<td>9.4 Build 1202</td>
	<td><a href="/download/postgresql-9.4-1202.jdbc4.jar">9.4-1202 JDBC 4</a></td>
	<td><a href="/download/postgresql-9.4-1202.jdbc41.jar">9.4-1202 JDBC 41</a></td>
	<td><a href="/download/postgresql-9.4-1202.jdbc42.jar">9.4-1202 JDBC 42</a></td>
	<td><a href="/download/postgresql-jdbc-9.4-1202.src.tar.gz">9.4-1202 JDBC Source</a></td>
</tr>
</table>
</div>

***
<a name="archived"></a>
## Archived Versions

<div class="tblBasic">
<table class="tblBasicGrey" style="width: 100%;" summary="Archived Versions" border="1" cellspacing="0" cellpadding="0">
<tr>
	<th>Version</th>
	<th>JDBC 2</th>
	<th>JDBC 2 EE</th>
	<th>JDBC 3</th>
	<th>JDBC 4</th>
	<th>JDBC 41</th>
	<th>Source</th>
</tr>

<tr>
	<td>9.3 Build 1104</td>
	<td>&nbsp;</td>
	<td>&nbsp;</td>
	<td>&nbsp;</td>
	<td><a href="/download/postgresql-9.3-1104.jdbc4.jar">9.3-1104 JDBC 4</a></td>
	<td><a href="/download/postgresql-9.3-1104.jdbc41.jar">9.3-1104 JDBC 41</a></td>
	<td><a href="/download/postgresql-jdbc-9.3-1104.src.tar.gz">9.3-1104 JDBC Source</a></td>
</tr>

<tr>
	<td>9.3 Build 1103</td>
	<td>&nbsp;</td>
	<td>&nbsp;</td>
	<td><a href="/download/postgresql-9.3-1103.jdbc3.jar">9.3-1103 JDBC 3</a></td>
	<td><a href="/download/postgresql-9.3-1103.jdbc4.jar">9.3-1103 JDBC 4</a></td>
	<td><a href="/download/postgresql-9.3-1103.jdbc41.jar">9.3-1103 JDBC 41</a></td>
	<td><a href="/download/postgresql-jdbc-9.3-1103.src.tar.gz">9.3-1103 JDBC Source</a></td>
</tr>
<tr>
	<td>9.2 Build 1004</td>
	<td>&nbsp;</td>
	<td>&nbsp;</td>
	<td><a href="/download/postgresql-9.2-1004.jdbc3.jar">9.2-1004 JDBC 3</a></td>
	<td><a href="/download/postgresql-9.2-1004.jdbc4.jar">9.2-1004 JDBC 4</a></td>
	<td><a href="/download/postgresql-9.2-1004.jdbc41.jar">9.2-1004 JDBC 41</a></td>
	<td><a href="/download/postgresql-jdbc-9.2-1004.src.tar.gz">9.2-1004 JDBC Source</a></td>
</tr>
<tr>
	<td>9.1 Build 903</td>
	<td>&nbsp;</td>
	<td>&nbsp;</td>
	<td><a href="/download/postgresql-9.1-903.jdbc3.jar">9.1-903 JDBC 3</a></td>
	<td><a href="/download/postgresql-9.1-903.jdbc4.jar">9.1-903 JDBC 4</a></td>
	<td>&nbsp;</td>
	<td><a href="/download/postgresql-jdbc-9.1-903.src.tar.gz">9.1-903 JDBC Source</a></td>
</tr>
<tr>
	<td>9.0 Build 802</td>
	<td>&nbsp;</td>
	<td>&nbsp;</td>
	<td><a href="/download/postgresql-9.0-802.jdbc3.jar">9.0-802 JDBC 3</a></td>
	<td><a href="/download/postgresql-9.0-802.jdbc4.jar">9.0-802 JDBC 4</a></td>
	<td>&nbsp;</td>
	<td><a href="/download/postgresql-jdbc-9.0-802.src.tar.gz">9.0-802 JDBC Source</a></td>
</tr>
<tr>
	<td>8.4 Build 703</td>
	<td>&nbsp;</td>
	<td>&nbsp;</td>
	<td><a href="/download/postgresql-8.4-703.jdbc3.jar">8.4-703 JDBC 3</a></td>
	<td><a href="/download/postgresql-8.4-703.jdbc4.jar">8.4-703 JDBC 4</a></td>
	<td>&nbsp;</td>
	<td><a href="/download/postgresql-jdbc-8.4-703.src.tar.gz">8.4-703 JDBC Source</a></td>
</tr>
<tr>
	<td>8.3 Build 607</td>
	<td><a href="/download/postgresql-8.3-607.jdbc2.jar">8.3-607 JDBC 2</a></td>
	<td><a href="/download/postgresql-8.3-607.jdbc2ee.jar">8.3-607 JDBC 2EE</a></td>
	<td><a href="/download/postgresql-8.3-607.jdbc3.jar">8.3-607 JDBC 3</a></td>
	<td><a href="/download/postgresql-8.3-607.jdbc4.jar">8.3-607 JDBC 4</a></td>
	<td>&nbsp;</td>
	<td><a href="/download/postgresql-jdbc-8.3-607.src.tar.gz">8.3-607 JDBC Source</a></td>
</tr>
<tr>
	<td>8.2 Build 512</td>
	<td><a href="/download/postgresql-8.2-512.jdbc2.jar">8.2-512 JDBC 2</a></td>
	<td><a href="/download/postgresql-8.2-512.jdbc2ee.jar">8.2-512 JDBC 2EE</a></td>
	<td><a href="/download/postgresql-8.2-512.jdbc3.jar">8.2-512 JDBC 3</a></td>
	<td><a href="/download/postgresql-8.2-512.jdbc4.jar">8.2-512 JDBC 4</a></td>
	<td>&nbsp;</td>
	<td><a href="/download/postgresql-jdbc-8.2-512.src.tar.gz">8.2-512 JDBC Source</a></td>
</tr>
</table>
</div>

<div class="tblBasic">
<table class="tblBasicGrey" style="width: 100%;" summary="Older Versions" border="1" cellspacing="0" cellpadding="0">
<tr>
	<th>Version</th>
	<th>JDBC 1</th>
	<th>JDBC 2</th>
	<th>JDBC 2 EE</th>
	<th>JDBC 3</th>
	<th>Source</th>
</tr>
<tr>
	<td>8.1 Build 415</td>
	<td>&nbsp;</td>
	<td><a href="/download/postgresql-8.1-415.jdbc2.jar">8.1-415 JDBC 2</a></td>
	<td><a href="/download/postgresql-8.1-415.jdbc2ee.jar">8.1-415 JDBC 2EE</a></td>
	<td><a href="/download/postgresql-8.1-415.jdbc3.jar">8.1-415 JDBC 3</a></td>
	<td><a href="/download/postgresql-jdbc-8.1-415.src.tar.gz">8.1-415 JDBC Source</a></td>
</tr>
<tr>
	<td>8.0 Build 325</td>
	<td>&nbsp;</td>
	<td><a href="/download/postgresql-8.0-325.jdbc2.jar">8.0-325 JDBC 2</a></td>
	<td><a href="/download/postgresql-8.0-325.jdbc2ee.jar">8.0-325 JDBC 2EE</a></td>
	<td><a href="/download/postgresql-8.0-325.jdbc3.jar">8.0-325 JDBC 3</a></td>
	<td><a href="/download/postgresql-jdbc-8.0-325.src.tar.gz">8.0-325 JDBC Source</a></td>
</tr>
<tr>
	<td>7.4 Build 216</td>
	<td><a href="/download/pg74.216.jdbc1.jar">pg74.216.jdbc1.jar</a></td>
	<td><a href="/download/pg74.216.jdbc2.jar">pg74.216.jdbc2.jar</a></td>
	<td><a href="/download/pg74.216.jdbc2ee.jar">pg74.216.jdbc2ee.jar</a></td>
	<td><a href="/download/pg74.216.jdbc3.jar">pg74.216.jdbc3.jar</a></td>
	<td>&nbsp;</td>
</tr>
<tr>
	<td>7.3 Build 113</td>
	<td><a href="/download/pg73jdbc1.jar">pg73jdbc1.jar</a></td>
	<td><a href="/download/pg73jdbc2.jar">pg73jdbc2.jar</a></td>
	<td><a href="/download/pg73jdbc2ee.jar">pg73jdbc2ee.jar</a></td>
	<td><a href="/download/pg73jdbc3.jar">pg73jdbc3.jar</a></td>
	<td>&nbsp;</td>
</tr>
<tr>
	<td>7.2</td>
	<td><a href="/download/pg72jdbc1.jar">pg72jdbc1.jar</a></td>
	<td><a href="/download/pg72jdbc2.jar">pg72jdbc2.jar</a></td>
	<td>&nbsp;</td>
	<td>&nbsp;</td>
	<td>&nbsp;</td>
</tr>
<tr>
	<td>7.1</td>
	<td><a href="/download/jdbc7.1-1.1.jar">jdbc7.1-1.1.jar</a></td>
	<td><a href="/download/jdbc7.1-1.2.jar">jdbc7.1-1.2.jar</a></td>
	<td>&nbsp;</td>
	<td>&nbsp;</td>
	<td>&nbsp;</td>
</tr>
<tr>
	<td>7.0</td>
	<td><a href="/download/jdbc7.0-1.1.jar">jdbc7.0-1.1.jar</a></td>
	<td><a href="/download/jdbc7.0-1.2.jar">jdbc7.0-1.2.jar</a></td>
	<td>&nbsp;</td>
	<td>&nbsp;</td>
	<td>&nbsp;</td>
</tr>
<tr>
	<td>6.5.2</td>
	<td><a href="/download/jdbc6.5-1.1.jar">jdbc6.5-1.1.jar</a></td>
	<td><a href="/download/jdbc6.5-1.2.jar">jdbc6.5-1.2.jar</a></td>
	<td>&nbsp;</td>
	<td>&nbsp;</td>
	<td>&nbsp;</td>
</tr>
<tr>
	<td>6.4</td>
	<td><a href="/download/jdbc6.4.jar">jdbc6.4.jar</a></td>
	<td>&nbsp;</td>
	<td>&nbsp;</td>
	<td>&nbsp;</td>
	<td>&nbsp;</td>
</tr>
<tr>
	<td>6.3</td>
	<td><a href="/download/jdbc6.3.jar">jdbc6.3.jar</a></td>
	<td>&nbsp;</td>
	<td>&nbsp;</td>
	<td>&nbsp;</td>
	<td>&nbsp;</td>
</tr>
<tr>
	<td>6.2.1</td>
	<td>&nbsp;</td>
	<td>&nbsp;</td>
	<td>&nbsp;</td>
	<td>&nbsp;</td>
	<td><a href="/download/jdbc6.2.1.tgz">jdbc6.2.1.tgz</a></td>
</tr>
<tr>
<td>0.4</td>
	  <td>&nbsp;</td>
	  <td>&nbsp;</td>
	  <td>&nbsp;</td>
	  <td>&nbsp;</td>
	  <td><a href="/download/JavaPostgres95-0.4.tar.gz">JavaPostgres95-0.4.tar.gz</a></td>
</tr>
<tr>
	<td>0.3</td>
	<td>&nbsp;</td>
	<td>&nbsp;</td>
	<td>&nbsp;</td>
	<td>&nbsp;</td>
	<td><a href="/download/JavaPostgres95-0.3.tar.gz">JavaPostgres95-0.3.tar.gz</a></td>
</tr>
<tr>
	<td>0.2</td>
	<td>&nbsp;</td>
	<td>&nbsp;</td>
	<td>&nbsp;</td>
	<td>&nbsp;</td>
	<td><a href="/download/JavaPostgres95-0.2.tar.gz">JavaPostgres95-0.2.tar.gz</a></td>
</tr>
</table>
</div>
