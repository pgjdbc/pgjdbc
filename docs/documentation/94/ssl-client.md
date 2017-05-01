---
layout: default_docs
title: Configuring the Client
header: Chapter 4. Using SSL
resource: media
previoustitle: Chapter 4. Using SSL
previous: ssl.html
nexttitle: Custom SSLSocketFactory
next: ssl-factory.html
---

Unlike psql and other libpq based programs the JDBC driver does server certificate
validation by default.  This means that when establishing a SSL connection the
JDBC driver will validate the server's identity preventing "man in the middle"
attacks. It does this by checking that the server certificate is signed by a
trusted authority. If you have a certificate signed by a global certificate
authority (CA), there is nothing further to do because Java comes with copies of
the most common CA's certificates. If you are dealing with a self-signed certificate
though, you need to make this available to the Java client to enable it to validate
the server's certificate.

> ### Note

> Only the JDBC 3 driver supports SSL. The 1.4 JDK was the first version to come
bundled with SSL support. Previous JDK versions that wanted to use SSL could
make use of the additional JSSE library, but it does not support the full range
of features utilized by the PostgreSQL™ JDBC driver.

To make the server certificate available to Java, the first step is to convert
it to a form Java understands.

`openssl x509 -in server.crt -out server.crt.der -outform der`

From here the easiest thing to do is import this certificate into Java's system
truststore.

`keytool -keystore $JAVA_HOME/lib/security/cacerts -alias postgresql -import -file server.crt.der`

The default password for the cacerts keystore is `changeit`. The alias to postgesql
is not important and you may select any name you desire.

If you do not have access to the system cacerts truststore you can create your
own truststore.

`keytool -keystore mystore -alias postgresql -import -file server.crt.der.`

When starting your Java application you must specify this keystore and password
to use.

`java -Djavax.net.ssl.trustStore=mystore -Djavax.net.ssl.trustStorePassword=mypassword com.mycompany.MyApp`

In the event of problems extra debugging information is available by adding
`-Djavax.net.debug=ssl` to your command line.

To instruct the JDBC driver to try and establish a SSL connection you must add
the connection URL parameter `ssl=true`.

<a name="nonvalidating"></a>
## Using SSL without Certificate Validation

In some situations it may not be possible to configure your Java environment to
make the server certificate available, for example in an applet.  For a large
scale deployment it would be best to get a certificate signed by recognized
certificate authority, but that is not always an option.  The JDBC driver provides
an option to establish a SSL connection without doing any validation, but please
understand the risk involved before enabling this option.

A non-validating connection is established via a custom `SSLSocketFactory` class
that is provided with the driver. Setting the connection URL parameter `sslfactory=org.postgresql.ssl.NonValidatingFactory`
will turn off all SSL validation.