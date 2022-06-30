---
layout: default_docs
title: Loading the Driver
header: Chapter 3. Initializing the Driver
resource: /documentation/head/media
previoustitle: Chapter 3. Initializing the Driver
previous: use.html
nexttitle: Connecting to the Database
next: connect.html
---
		
Applications do not need to explicitly load the org.postgresql.Driver
class because the pgjdbc driver jar supports the Java Service Provider
mechanism. The driver will be loaded by the JVM when the application
connects to PostgreSQL™ (as long as the driver's jar file is on the
classpath).


### Note

Prior to Java 1.6, the driver had to be loaded by the application - either by calling

```java
Class.forName("org.postgresql.Driver");
```
or by passing the driver class name as a JVM parameter.

`java -Djdbc.drivers=org.postgresql.Driver example.ImageViewer`

These older methods of loading the driver are still supported but they are no longer necessary.
