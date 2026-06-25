---
title: "Setting up the Class Path"
aliases:
    - "/documentation/head/classpath.html"
    - "/documentation/80/classpath.html"
    - "/documentation/81/classpath.html"
    - "/documentation/82/classpath.html"
    - "/documentation/83/classpath.html"
    - "/documentation/84/classpath.html"
    - "/documentation/85/classpath.html"
    - "/documentation/90/classpath.html"
    - "/documentation/91/classpath.html"
    - "/documentation/92/classpath.html"
    - "/documentation/93/classpath.html"
    - "/documentation/94/classpath.html"
---


To use the driver, the JAR archive named `postgresql-MM.nn.pp.jar` needs to be included in the class path, either by putting it in the `CLASSPATH` environment variable, or by using flags on the **java** command line.

For instance, assume we have an application that uses the JDBC driver to access a database, and that application is installed as `/usr/local/lib/myapp.jar` . The PostgreSQL® JDBC driver installed as `/usr/local/pgsql/share/java/postgresql-MM.nn.pp.jar` .
To run the application, we would use:

```bash
export CLASSPATH=/usr/local/lib/myapp.jar:/usr/local/pgsql/share/java/postgresql-42.5.0.jar:. java MyApp
```

Current Java applications will likely use maven, gradle or some other package manager. [Use this to search](https://mvnrepository.com/artifact/org.postgresql/postgresql) for the latest jars and how to include them in your project

Loading the driver from within the application is covered in [Initializing the Driver](/documentation/use/).

