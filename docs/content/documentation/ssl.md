---
title: "Using SSL"
date: 2022-06-19T22:46:55+05:30
draft: false
weight: 3
toc: true
aliases:
    - "/documentation/head/ssl-client.html"
---

Configuring the PostgreSQL® server for SSL is covered in the [main documentation](https://www.postgresql.org/docs/current/ssl-tcp.html), so it will not be repeated here. There are also instructions in the source [certdir](https://github.com/pgjdbc/pgjdbc/tree/master/certdir)
Before trying to access your SSL enabled server from Java, make sure
you can get to it via **psql**. You should see output like the following
if you have established a SSL  connection.

```bash
$ ./bin/psql -h localhost -U postgres
psql (14.5)
SSL connection (protocol: TLSv1.2, cipher: ECDHE-RSA-AES256-GCM-SHA384, bits: 256, compression: off)
Type "help" for help.

postgres=#
```

## Custom SSLSocketFactory

PostgreSQL® provides a way for developers to customize how an SSL connection is established. This may be used to provide
a custom certificate source or other extensions by allowing the developer to create their own `SSLContext` instance.
The connection URL parameters `sslfactory` allow the user to specify which custom class to use for creating the 
`SSLSocketFactory` . The class name specified by `sslfactory` must extend `javax.net.ssl.SSLSocketFactory` and be 
available to the driver's classloader.

This class must have a zero argument constructor or a single argument constructor preferentially taking
a `Properties` argument. There is a simple `org.postgresql.ssl.DefaultJavaSSLFactory` provided which uses the
default java SSLFactory.

Information on how to actually implement such a class is beyond the scope of this documentation. Places to look for help
are the [JSSE Reference Guide](https://docs.oracle.com/javase/8/docs/technotes/guides/security/jsse/JSSERefGuide.html)
and the source to the `NonValidatingFactory` provided by the JDBC driver.

## Configuring the Client

There are a number of connection parameters for configuring the client for SSL. See [SSL Connection parameters](/documentation/use/#connection-parameters/)

The simplest being `ssl=true` , passing this into the driver will cause the driver to validate both the SSL certificate
and verify the hostname (same as `verify-full` ).

> **Note**
>
> This is different from libpq which defaults to a non-validating SSL connection.

In this mode, when establishing a SSL connection the JDBC driver will validate the server's identity preventing 
"man in the middle" attacks. It does this by checking that the server certificate is signed by a trusted authority, 
and that the host you are connecting to is the same as the hostname in the certificate.

If you **require** encryption and want the connection to fail if it can't be encrypted then set `sslmode=require` 
this ensures that the server is configured to accept SSL connections for this Host/IP address and that the server recognizes 
the client certificate. In other words if the server does not accept SSL connections or the client certificate is not 
recognized the connection will fail.

> **Note**
>
> In this mode we will accept all server certificates.

If `sslmode=verify-ca` , the server is verified by checking the certificate chain up to the root certificate stored on the client.

If `sslmode=verify-full` , the server host name will be verified to make sure it matches the name stored in the server certificate.

The SSL connection will fail if the server certificate cannot be verified. `verify-full` is recommended in most 
security-sensitive environments.

The default SSL Socket factory is the LibPQFactory. In the case where the certificate validation is failing you can 
try `sslcert=` and LibPQFactory will not send the client certificate. If the server is not configured to authenticate 
using the certificate it should connect.

The location of the client certificate, the PKCS-8 client key and root certificate can be overridden with the `sslcert` , 
`sslkey` , and `sslrootcert` settings respectively. These default to /defaultdir/postgresql.crt, `/defaultdir/postgresql.pk8`,
and `/defaultdir/root.crt` respectively where defaultdir is `${user.home}/.postgresql/` in *nix systems and `%appdata%/postgresql/` 
on windows.

As of version 42.2.9 PKCS-12 is also supported. In this archive format the client key and the client certificate are in 
one file, which needs to be set with the `sslkey` parameter. For the PKCS-12 format to be recognized, the file extension 
must be ".p12" (supported since 42.2.9) or ".pfx" (since 42.2.16). (In this case the `sslcert` parameter is ignored.)

> **NOTE**
>
> When using a PKCS-12 client certificate the name or alias *MUST* be `user` when using `openssl pkcs12 -export -name user ...`
There are complete examples of how to export the certificate in the [certdir](https://raw.githubusercontent.com/pgjdbc/pgjdbc/master/certdir/Makefile) Makefile

Finer control of the SSL connection can be achieved using the `sslmode` connection parameter.
This parameter is the same as the libpq `sslmode` parameter and currently implements the
following

|sslmode|Eavesdropping Protection|MITM Protection||
|---|---|---|---|
|disable|No |No|I don't care about security and don't want to pay the overhead for encryption|
|allow|Maybe |No|I don't care about security but will pay the overhead for encryption if the server insists on it|
|prefer|Maybe|No|I don't care about encryption but will pay the overhead of encryption if the server supports it|
|require|Yes |No|I want my data to be encrypted, and I accept the overhead. I trust that the network will make sure I always connect to the server I want.|
|verify-ca|Yes|Depends on CA policy|I want my data encrypted, and I accept the overhead. I want to be sure that I connect to a server that I trust.|
|verify-full|Yes |Yes|I want my data encrypted, and I accept the overhead. I want to be sure that I connect to a server I trust, and that it's the one I specify.|

> **NOTE**
>
> If you are using Java's default mechanism (not LibPQFactory) to create the SSL connection you will need to make the server certificate available to Java, the first step is to convert it to a form Java understands.

 `openssl x509 -in server.crt -out server.crt.der -outform der`

From here the easiest thing to do is import this certificate into Java's system truststore.

 `keytool -keystore $JAVA_HOME/lib/security/cacerts -alias postgresql -import -file server.crt.der`

The default password for the cacerts keystore is `changeit` . Setting the alias to postgresql is not required.  You may apply any name you wish.

If you do not have access to the system cacerts truststore you can create your own truststore.

 `keytool -keystore mystore -alias postgresql -import -file server.crt.der`

When starting your Java application you must specify this keystore and password to use.

 `java -Djavax.net.ssl.trustStore=mystore -Djavax.net.ssl.trustStorePassword=mypassword com.mycompany.MyApp`

In the event of problems extra debugging information is available by adding `-Djavax.net.debug=ssl` to your command line.

### Using SSL without Certificate Validation

In some situations it may not be possible to configure your Java environment to make the server certificate available, for example in an applet. For a large scale deployment it would be best to get a certificate signed by recognized
certificate authority, but that is not always an option. The JDBC driver provides an option to establish a SSL connection without doing any validation, but please understand the risk involved before enabling this option.

A non-validating connection is established via a custom `SSLSocketFactory` class that is provided with the driver. Setting the connection URL parameter `sslfactory=org.postgresql.ssl.NonValidatingFactory` will turn off all SSL validation.
