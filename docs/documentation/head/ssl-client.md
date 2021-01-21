---
layout: default_docs
title: Configuring the Client
header: Chapter 4. Using SSL
resource: /documentation/head/media
previoustitle: Chapter 4. Using SSL
previous: ssl.html
nexttitle: Custom SSLSocketFactory
next: ssl-factory.html
---

There are a number of connection parameters for configuring the client for SSL. See [SSL Connection parameters](connect.html#ssl)

The simplest being `ssl=true`, passing this into the driver will cause the driver to validate both
the SSL certificate and verify the hostname (same as `verify-full`). **Note** this is different than
libpq which defaults to a non-validating SSL connection.

In this mode, when establishing a SSL connection the JDBC driver will validate the server's
identity preventing "man in the middle" attacks. It does this by checking that the server
certificate is signed by a trusted authority, and that the host you are connecting to is the
same as the hostname in the certificate.

If you **require** encryption and want the connection to fail if it can't be encrypted then set
`sslmode=require` this ensures that the server is configured to accept SSL connections for this
Host/IP address and that the server recognizes the client certificate. In other words if the server
does not accept SSL connections or the client certificate is not recognized the connection will fail.
**Note** in this mode we will accept all server certificates.

If `sslmode=verify-ca`, the server is verified by checking the certificate chain up to the root
certificate stored on the client.

If `sslmode=verify-full`, the server host name will be verified to make sure it matches the name
stored in the server certificate.

The SSL connection will fail if the server certificate cannot be verified. `verify-full` is recommended
in most security-sensitive environments.

The default SSL Socket factory is the LibPQFactory
In the case where the certificate validation is failing you can try `sslcert=` and LibPQFactory will
not send the client certificate. If the server is not configured to authenticate using the certificate
it should connect.

The location of the client certificate, the PKCS-8 client key and root certificate can be overridden with the
`sslcert`, `sslkey`, and `sslrootcert` settings respectively. These default to /defaultdir/postgresql.crt,
/defaultdir/postgresql.pk8, and /defaultdir/root.crt respectively where defaultdir is
${user.home}/.postgresql/ in *nix systems and %appdata%/postgresql/ on windows

As of version 42.2.9 PKCS-12 is also supported. In this archive format the client key and the client
certificate are in one file, which needs to be set with the `sslkey` parameter. For the PKCS-12 format
to be recognized, the file extension must be ".p12" (supported since 42.2.9) or ".pfx" (since 42.2.16).
(In this case the `sslcert` parameter is ignored.)

Finer control of the SSL connection can be achieved using the `sslmode` connection parameter.
This parameter is the same as the libpq `sslmode` parameter and currently implements the
following

<div class="tblBasic">
<table class="tblBasicWhite" border="1" summary="SSL Mode Descriptions" cellspacing="0" cellpadding="0">
<thead>
<tr>
  <th>sslmode</th><th>Eavesdropping Protection</th><th> MITM Protection</th><th/>
</tr>
</thead>
<tr>
  <td>disable</td><td>No</td><td>No</td><td>I don't care about security and don't want to pay the overhead for encryption</td>
</tr>
<tr>
  <td>allow</td><td>Maybe</td><td>No</td><td>I don't care about security but will pay the overhead for encryption if the server insists on it</td>
</tr>
<tr>
  <td>prefer</td><td>Maybe</td><td>No</td><td>I don't care about encryption but will pay the overhead of encryption if the server supports it</td>
</tr>
<tr>
  <td>require</td><td>Yes</td><td>No</td><td>I want my data to be encrypted, and I accept the overhead. I trust that the network will make sure I always connect to the server I want.</td>
</tr>
<tr>
  <td>verify-ca</td><td>Yes</td><td>Depends on CA policy</td><td>I want my data encrypted, and I accept the overhead. I want to be sure that I connect to a server that I trust.</td>
</tr>
<tr>
  <td>verify-full</td><td>Yes</td><td>Yes</td><td>I want my data encrypted, and I accept the overhead. I want to be sure that I connect to a server I trust, and that it's the one I specify.</td>
</tr>
</table>
</div>


### Note

If you are using Java's default mechanism (not LibPQFactory) to create the SSL connection you will
need to make the server certificate available to Java, the first step is to convert
it to a form Java understands.

`openssl x509 -in server.crt -out server.crt.der -outform der`

From here the easiest thing to do is import this certificate into Java's system
truststore.

`keytool -keystore $JAVA_HOME/lib/security/cacerts -alias postgresql -import -file server.crt.der`

The default password for the cacerts keystore is `changeit`. Setting the alias to postgresql
is not required.  You may apply any name you wish.

If you do not have access to the system cacerts truststore you can create your
own truststore.

`keytool -keystore mystore -alias postgresql -import -file server.crt.der`

When starting your Java application you must specify this keystore and password
to use.

`java -Djavax.net.ssl.trustStore=mystore -Djavax.net.ssl.trustStorePassword=mypassword com.mycompany.MyApp`

In the event of problems extra debugging information is available by adding
`-Djavax.net.debug=ssl` to your command line.

<a name="nonvalidating"></a>
## Using SSL without Certificate Validation

In some situations it may not be possible to configure your Java environment to
make the server certificate available, for example in an applet.  For a large
scale deployment it would be best to get a certificate signed by recognized
certificate authority, but that is not always an option.  The JDBC driver provides
an option to establish a SSL connection without doing any validation, but please
understand the risk involved before enabling this option.

A non-validating connection is established via a custom `SSLSocketFactory` class that is provided
with the driver. Setting the connection URL parameter `sslfactory=org.postgresql.ssl.NonValidatingFactory`
will turn off all SSL validation.
