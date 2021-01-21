---
layout: default_docs
title: Chapter 4. Using SSL
header: Chapter 4. Using SSL
resource: /documentation/head/media
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
documentation](https://www.postgresql.org/docs/current/ssl-tcp.html),
so it will not be repeated here. There are also instructions in the source 
[certdir](https://github.com/pgjdbc/pgjdbc/tree/master/certdir)
Before trying to access your SSL enabled server from Java, make sure 
you can get to it via **psql**. You should see output like the following 
if you have established a SSL  connection. 

```
$ ./bin/psql -h localhost -U postgres
psql (9.6.2)
SSL connection (protocol: TLSv1.2, cipher: ECDHE-RSA-AES256-GCM-SHA384, bits: 256, compression: off)
Type "help" for help.

postgres=#
```
