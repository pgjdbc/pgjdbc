---
title: Logging using java.util.logging
date: 2022-06-19T22:46:55+05:30
draft: false
weight: 1
---

<a name="overview"></a>
# Overview

The PostgreSQL JDBC Driver supports the use of logging (or tracing) to help resolve issues with the
PgJDBC Driver when is used in your application.

The PgJDBC Driver uses the logging APIs of `java.util.logging` that is part of Java since JDK 1.4,
which makes it a good choice for the driver since it doesn't add any external dependency for a logging
framework. `java.util.logging` is a very rich and powerful tool, it's beyond the scope of these docs
to explain how to use it to it's full potential, for that please refer to
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

The root logger used by the PgJDBC driver is `org.postgresql`.

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
