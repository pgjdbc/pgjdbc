---
title: "SSL / TLS"
description: "Configuring TLS for pgJDBC: `sslmode` levels (`disable` through `verify-full`), certificate and key file formats, the custom `SSLSocketFactory` SPI for application-managed key material, and the channel-binding interaction with SCRAM."
date: 2022-06-19T22:46:55+05:30
draft: false
weight: 3
toc: true
last_reviewed: "2026-05-21"
aliases:
    - "/documentation/ssl/"
    - "/documentation/head/ssl-client.html"
---

Configuring the PostgreSQL® server for SSL is covered in the [main documentation](https://www.postgresql.org/docs/current/ssl-tcp.html), so it will not be repeated here. There are also instructions in the source [certdir](https://github.com/pgjdbc/pgjdbc/tree/master/certdir).
Before trying to access your SSL enabled server from Java, make sure
you can get to it via **psql**. You should see output like the following
if you have established an SSL connection.

```bash
$ ./bin/psql -h localhost -U postgres
psql (14.5)
SSL connection (protocol: TLSv1.2, cipher: ECDHE-RSA-AES256-GCM-SHA384, bits: 256, compression: off)
Type "help" for help.

postgres=#
```

## Custom SSLSocketFactory

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- PGProperty.java | pgjdbc/src/main/java/org/postgresql/PGProperty.java | 936-956
- SocketFactoryFactory.java | pgjdbc/src/main/java/org/postgresql/core/SocketFactoryFactory.java | 56-70
- ObjectFactory.java | pgjdbc/src/main/java/org/postgresql/util/ObjectFactory.java | 20-68
- DefaultJavaSSLFactory.java | pgjdbc/src/main/java/org/postgresql/ssl/DefaultJavaSSLFactory.java | 17-21
- NonValidatingFactory.java | pgjdbc/src/main/java/org/postgresql/ssl/NonValidatingFactory.java | 15-36
{{< /review >}}

PostgreSQL® provides a way for developers to customize how an SSL connection is established. This may be used to provide
a custom certificate source or other extensions by allowing the developer to create their own `SSLContext` instance.
The connection URL parameter `sslfactory` allows the user to specify which custom class to use for creating the
`SSLSocketFactory`. The class name specified by `sslfactory` must extend `javax.net.ssl.SSLSocketFactory` and be
available to the driver's classloader.

This class must have a `Properties` constructor, a single `String` constructor for `sslfactoryarg`, or a zero-argument
constructor; the driver tries them in that order. There is a simple `org.postgresql.ssl.DefaultJavaSSLFactory`
provided which uses the default Java `SSLSocketFactory`.

Information on how to actually implement such a class is beyond the scope of this documentation. Places to look for help
are the [JSSE Reference Guide](https://docs.oracle.com/javase/8/docs/technotes/guides/security/jsse/JSSERefGuide.html)
and the source to the `NonValidatingFactory` provided by the JDBC driver.

## Configuring the Client

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- PGProperty.java | pgjdbc/src/main/java/org/postgresql/PGProperty.java | 913-1063
- SslMode.java | pgjdbc/src/main/java/org/postgresql/jdbc/SslMode.java | 15-80
- SslNegotiation.java | pgjdbc/src/main/java/org/postgresql/jdbc/SslNegotiation.java | 8-29
- ConnectionFactoryImpl.java | pgjdbc/src/main/java/org/postgresql/core/v3/ConnectionFactoryImpl.java | 272-280
- ConnectionFactoryImpl.java | pgjdbc/src/main/java/org/postgresql/core/v3/ConnectionFactoryImpl.java | 644-717
- MakeSSL.java | pgjdbc/src/main/java/org/postgresql/ssl/MakeSSL.java | 33-67
- LibPQFactory.java | pgjdbc/src/main/java/org/postgresql/ssl/LibPQFactory.java | 119-217
{{< /review >}}

There are a number of connection parameters for configuring the client for SSL. See [SSL Connection parameters](/documentation/reference/connection-properties/)

The simplest is `ssl=true`; passing this into the driver causes the driver to validate the SSL certificate
and verify the hostname (the same behavior as `sslmode=verify-full`).

> **Note**
>
> This is different from libpq which defaults to a non-validating SSL connection.

In this mode, when establishing an SSL connection the JDBC driver validates the server's identity, preventing
"man in the middle" attacks. It does this by checking that the server certificate is signed by a trusted authority,
and that the host you are connecting to is the same as the hostname in the certificate.

If you **require** encryption and want the connection to fail if it can't be encrypted, set `sslmode=require`.
This ensures that the server accepts SSL connections for this Host/IP address. If the server does not accept SSL
connections, the connection will fail. If the server is configured to authenticate clients with certificates, then
the client certificate must also be recognized for authentication to succeed.

> **Note**
>
> In this mode we will accept all server certificates.

If `sslmode=verify-ca`, the server is verified by checking the certificate chain up to the root certificate stored on the client.

If `sslmode=verify-full`, the server host name will be verified to make sure it matches the name stored in the server certificate.

The SSL connection will fail if the server certificate cannot be verified. `verify-full` is recommended in most 
security-sensitive environments.


There is a property `sslNegotiation`. This defaults to `postgres`, but if set to `direct` it enables a second way to initiate
SSL encryption. The server will recognize connections which immediately begin SSL negotiation
without any previous SSLRequest packets. See [Protocol Flow](https://www.postgresql.org/docs/17/protocol-flow.html#PROTOCOL-FLOW-SSL) for more details.

The default SSL socket factory is `LibPQFactory`. If the server rejects the client certificate, pass `sslcert=`
(empty value) so the driver does not present a client certificate. The connection then succeeds as long as the
server does not require client-certificate authentication. This does not help when the *server* certificate fails
trust validation on the client side; see `sslmode` and `sslrootcert` for that.

Recent OpenSSL versions no longer support the older PKCS-8 conversion recipe shown below without enabling legacy
algorithms. As a result, PKCS-12 keys are the most portable choice for new client-key material.

The location of the client certificate, client key and root certificate can be overridden with the `sslcert`,
`sslkey`, and `sslrootcert` settings respectively. These default to `/defaultdir/postgresql.crt`,
`/defaultdir/postgresql.pk8`, and `/defaultdir/root.crt` respectively where `defaultdir` is
`${user.home}/.postgresql/` in *nix systems and `%appdata%/postgresql/` on Windows.

PKCS-12 is also supported. In this archive format the client key and the client certificate are in one file, which
needs to be set with the `sslkey` parameter. For the PKCS-12 format to be recognized, the file extension must be
`.p12` (supported since 42.2.9) or `.pfx` (since 42.2.16). In this case the `sslcert` parameter is ignored.

> **NOTE**
>
> When using a PKCS-12 client certificate the name or alias *MUST* be `user` when using `openssl pkcs12 -export -name user ...`.
There are complete examples of how to export the certificate in the [certdir](https://raw.githubusercontent.com/pgjdbc/pgjdbc/master/certdir/Makefile) Makefile.

### Bringing your own client key

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- LibPQFactory.java | pgjdbc/src/main/java/org/postgresql/ssl/LibPQFactory.java | 74-145
- LazyKeyManager.java | pgjdbc/src/main/java/org/postgresql/ssl/LazyKeyManager.java | 142-286
- PKCS12KeyManager.java | pgjdbc/src/main/java/org/postgresql/ssl/PKCS12KeyManager.java | 47-85
- PEMKeyManager.java | pgjdbc/src/main/java/org/postgresql/ssl/PEMKeyManager.java | 42-101
- BaseX509KeyManager.java | pgjdbc/src/main/java/org/postgresql/ssl/BaseX509KeyManager.java | 56-97
- certdir/Makefile | certdir/Makefile | 19-21
- PEMKeyManagerTest.java | pgjdbc/src/test/java/org/postgresql/test/ssl/PEMKeyManagerTest.java | 160-199
- PKCS12KeyManagerTest.java | pgjdbc/src/test/java/org/postgresql/test/ssl/PKCS12KeyManagerTest.java | 28-55
{{< /review >}}

The `sslkey` file can be in [PKCS-12](https://en.wikipedia.org/wiki/PKCS_12), in [PKCS-8](https://en.wikipedia.org/wiki/PKCS_8)
[DER format](https://wiki.openssl.org/index.php/DER), or in PEM format when the filename ends with `.key` or `.pem`.
To convert a PEM key to PKCS-8 DER format:

```sh
openssl pkcs8 -topk8 -inform PEM -in postgresql.key -outform DER -out postgresql.pk8 -v1 PBE-MD5-DES
```

If your PKCS-8 DER or PKCS-12 key has a password, supply it via the `sslpassword` connection parameter. If the
PKCS-8 DER key has no password, add `-nocrypt` to the command above so the driver does not prompt for one. For a
PKCS-12 export the `alias`/`name` field on the key must be `user`, for example:

```sh
openssl pkcs12 -export -in client.crt -inkey client.key -out client.p12 -name user -CAfile root.crt -caname local -passout pass:<password>
```

> **NOTE**
>
> The `-v1 PBE-MD5-DES` cipher used in the PEM→DER conversion above may be inadequate in environments with high security requirements, especially if the key file is not otherwise protected (OS access control) or is transmitted over untrusted channels. The driver relies on the cryptography providers shipped with the Java runtime; the recipe above is known to work only when the OpenSSL and Java providers support that legacy algorithm. For stricter requirements, prefer PKCS-12 or see this [Stack Overflow discussion](https://stackoverflow.com/questions/58488774/configure-tomcat-hibernate-to-have-a-cryptographic-provider-supporting-1-2-840-1) on selecting a stronger cipher suite.

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

The default password for the cacerts keystore is `changeit`. Setting the alias to postgresql is not required. You may apply any name you wish.

If you do not have access to the system cacerts truststore you can create your own truststore.

 `keytool -keystore mystore -alias postgresql -import -file server.crt.der`

When starting your Java application you must specify this keystore and password to use.

 `java -Djavax.net.ssl.trustStore=mystore -Djavax.net.ssl.trustStorePassword=mypassword com.mycompany.MyApp`

In the event of problems extra debugging information is available by adding `-Djavax.net.debug=ssl` to your command line.

### Using SSL without Certificate Validation

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- NonValidatingFactory.java | pgjdbc/src/main/java/org/postgresql/ssl/NonValidatingFactory.java | 15-52
- LibPQFactory.java | pgjdbc/src/main/java/org/postgresql/ssl/LibPQFactory.java | 147-152
{{< /review >}}

In some situations it may not be possible to configure your Java environment to make the server certificate available, for example in an applet. For a large-scale deployment it would be best to get a certificate signed by a recognized
certificate authority, but that is not always an option. The JDBC driver provides an option to establish an SSL connection without doing any validation, but please understand the risk involved before enabling this option.

A non-validating connection is established via a custom `SSLSocketFactory` class that is provided with the driver. Setting the connection URL parameter `sslfactory=org.postgresql.ssl.NonValidatingFactory` turns off all SSL validation.
