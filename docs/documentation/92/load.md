---
layout: default_docs
title: Loading the Driver
header: Chapter 3. Initializing the Driver
resource: media
previoustitle: Chapter 3. Initializing the Driver
previous: use.html
nexttitle: Connecting to the Database
next: connect.html
---
		
Before you can connect to a database, you need to load the driver. There are two
methods available, and it depends on your code which is the best one to use.

In the first method, your code implicitly loads the driver using the `Class.forName()`
method. For PostgreSQL™, you would use:

Class.forName("org.postgresql.Driver");

This will load the driver, and while loading, the driver will automatically
register itself with JDBC.

### Note

The `forName()` method can throw a `ClassNotFoundException` if the driver is not
available.

This is the most common method to use, but restricts your code to use just PostgreSQL™.
If your code may access another database system in the future, and you do not
use any PostgreSQL™-specific extensions, then the second method is advisable.

The second method passes the driver as a parameter to the JVM as it starts, using
the `-D` argument. Example:

`java -Djdbc.drivers=org.postgresql.Driver example.ImageViewer`

In this example, the JVM will attempt to load the driver as part of its initialization.
Once done, the ImageViewer is started.

Now, this method is the better one to use because it allows your code to be used
with other database packages without recompiling the code. The only thing that
would also change is the connection URL, which is covered next.

One last thing: When your code then tries to open a `Connection`, and you get a
No driver available `SQLException` being thrown, this is probably caused by the
driver not being in the class path, or the value in the parameter not being
correct.