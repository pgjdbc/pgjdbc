---
layout: default_docs
title: Chapter 4. Using SSL
header: Chapter 4. Using SSL
resource: media
previoustitle: Connecting to the Database
previous: connect.html
nexttitle: Configuring the Client
next: ssl-client.html
---

**Table of Contents**

* [Configuring the Server](ssl.html#ssl-server)
* [Configuring the Client](ssl-client.html)
	* [Using SSL without Certificate Validation](ssl-client.html#nonvalidating)
* [Custom SSLSocketFactory](ssl-factory.html)

<a name="ssl-server"></a>
# Configuring the Server

Configuring the PostgreSQL™ server for SSL is covered in the [main
documentation](http://www.postgresql.org/docs/current/static/ssl-tcp.html),
so it will not be repeated here. Before trying to access your SSL enabled
server from Java, make sure you can get to it via **psql**. You should
see output like the following if you have established a SSL  connnection. 

`$ ./bin/psql -h localhost`  
`Welcome to psql 8.0.0rc5, the PostgreSQL interactive terminal.`

`Type: \copyright for distribution terms`  
&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;`\h for help with SQL commands`  
&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;`\? for help with psql commands`  
&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;`\g or terminate with semicolon to execute query`  
&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;`\q to quit`

`SSL connection (cipher: DHE-RSA-AES256-SHA, bits: 256)`