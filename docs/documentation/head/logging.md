---
layout: default_docs
title: Chapter 12. Logging using java.util.logging
header: Chapter 12. Logging using java.util.logging
resource: /documentation/head/media
previoustitle: Data Sources and JNDI
previous: jndi.html
nexttitle: Further Reading
next: reading.html
---

**Table of Contents**

* [Overview](logging.html#overview)
* [Configuration](logging.html#configuration)
  * [Enable logging by using connection properties](logging.html#conprop)
  * [Enable logging by using logging.properties file](logging.html#fileprop)

<a name="overview"></a>
# Overview

The PostgreSQL JDBC Driver supports the use of logging (or tracing) to help resolve issues with the
PgJDBC Driver when is used in your application.

The PgJDBC Driver uses the logging APIs of `java.util.logging` that is part of Java since JDK 1.4,
which makes it a good choice for the driver since it don't add any external dependency for a logging
framework. `java.util.logging` is a very rich and powerful tool, it's beyond the scope of this docs
to explain or use it full potential, for that please refer to
[Java Logging Overview](https://docs.oracle.com/javase/8/docs/technotes/guides/logging/overview.html).

This logging support was added since version 42.0.0 of the PgJDBC Driver, and previous
versions uses a custom mechanism to enable logging that it is replaced by the use of
`java.util.logging` in current versions, the old mechanism is no longer available.

Please note that while most people asked the use of a Logging Framework for a long time, this
support is mainly to debug the driver itself and not for general sql query debug.

<a name="configuration"></a>
# Configuration

The Logging APIs offer both static and dynamic configuration control. Static control enables field
service staff to set up a particular configuration and then re-launch the application with the new
logging settings. Dynamic control allows for updates to the logging configuration within a currently
running program.

As part of the support of a logging framework in the PgJDBC Driver, there was a need to facilitate
the enabling of the Logger using [connection properties](logging.html#conprop), which uses a static
control to enable the tracing in the driver. Keep in mind that if you use an Application Server
(Tomcat, JBoss, WildFly, etc.) you should use the facilities provided by them to enable the logging,
as most Application Servers use dynamic configuration control which makes easy to enable/disable
logging at runtime.

The root logger used by the PgJDBC driver is `org.postgresql`.

<a name="conprop"></a>
## Enable logging by using connection properties

The driver provides a facility to enable logging using connection properties, it's not as feature rich
as using a `logging.properties` file, so it should be used when you are really debugging the driver.

The properties are `loggerLevel` and `loggerFile`:

**loggerLevel**: Logger level of the driver. Allowed values: `OFF`, `DEBUG` or `TRACE`.

This option enable the `java.util.logging.Logger` Level of the driver based on the following mapping:

<table summary="Logger Level mapping" class="CALSTABLE" border="1">
  <tr>
    <th>loggerLevel</th>
    <th>java.util.logging</th>
  </tr>
  <tbody>
    <tr>
      <td>OFF</td>
      <td>OFF</td>
    </tr>
    <tr>
      <td>DEBUG</td>
      <td>FINE</td>
    </tr>
    <tr>
      <td>TRACE</td>
      <td>FINEST</td>
    </tr>
  </tbody>
</table>

As noted, there are no other levels supported using this method, and internally the driver Logger levels
should not (for the most part) use others levels as the intention is to debug the driver and don't
interfere with higher levels when some applications enable them globally.

**loggerFile**: File name output of the Logger.

If set, the Logger will use a `java.util.logging.FileHandler` to write to a specified file.
If the parameter is not set or the file can't be created the `java.util.logging.ConsoleHandler`
will be used instead.

This parameter should be use together with `loggerLevel`.

The following is an example of how to use connection properties to enable logging:

```
jdbc:postgresql://localhost:5432/mydb?loggerLevel=DEBUG
jdbc:postgresql://localhost:5432/mydb?loggerLevel=TRACE&loggerFile=pgjdbc.log
```

<a name="fileprop"></a>
## Enable logging by using logging.properties file

The default Java logging framework stores its configuration in a file called `logging.properties`.
Settings are stored per line using a dot notation format. Java installs a global configuration file
in the `lib` folder of the Java installation directory, although you can use a separate configuration
file by specifying the `java.util.logging.config.file` property when starting a Java program.
`logging.properties` files can also be created and stored with individual projects.

The following is an example of setting that you can make in the `logging.properties`:

```properties
# Specify the handler, the handlers will be installed during VM startup.
handlers = java.util.logging.FileHandler

# Default global logging level.
.level = OFF

# Default file output is in user's home directory.
java.util.logging.FileHandler.pattern = %h/pgjdbc%u.log
java.util.logging.FileHandler.limit = 5000000
java.util.logging.FileHandler.count = 20
java.util.logging.FileHandler.formatter = java.util.logging.SimpleFormatter
java.util.logging.FileHandler.level = FINEST

java.util.logging.SimpleFormatter.format = %1$tY-%1$tm-%1$td %1$tH:%1$tM:%1$tS %4$s %2$s %5$s%6$s%n

# Facility specific properties.
org.postgresql.level = FINEST
```

And when you run your application you pass the system property:
`java -jar -Djava.util.logging.config.file=logging.properties run.jar`

