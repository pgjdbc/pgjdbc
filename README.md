## Klipfolio Fork Setup Instructions

This Klipfolio PostgreSQL JDBC driver fork is based on version 9.1 stable and is configured to only build the JDBC4 variant.

1. Checkout code to your local dev env.
2. Make sure you have Maven (latest version is ok) setup properly.
3. Run `mvn compile` to compile the code and make sure there's no errors.
4. Run the following `Docker` commands to start PostgresSQL server in order to run the tests.
```
docker pull postgres:9.1.24
docker run --name testPostgresDb -p 5455:5432 -e POSTGRES_USER=test -e POSTGRES_PASSWORD=password -e POSTGRES_DB=test -d postgres:9.1.24
```
5. Once the server is running, you can now run `mvn test` to run the tests and make sure it passes without error.
6. Run `mvn install` to install this version of PostgresSQL JDBC driver to your local maven repository.


## Previous README

This is a simple readme describing how to compile and use the jdbc driver.

---------------------------------------------------------------------------

This isn't a guide on how to use JDBC - for that refer to Sun's website:

	http://java.sun.com/products/jdbc/

For problems with this driver, refer to driver's home page:

	http://jdbc.postgresql.org/

and associated mailing list:

	http://archives.postgresql.org/pgsql-jdbc/

---------------------------------------------------------------------------

COMPILING

To compile you will need to have Ant installed. To obtain Ant go to
http://ant.apache.org/index.html and download the binary. Being pure
java it will run on virtually all java platforms. If you have any problems
please email the jdbc list.

Once you have Ant, simply run ant in the top level directory.  This will
compile the correct driver for your JVM, and build a .jar file (Java ARchive)
called postgresql.jar.

REMEMBER: Once you have compiled the driver, it will work on ALL platforms
that support that version of the API. You don't need to build it for each
platform.

If you are having problems, prebuilt versions of the driver 
are available at http://jdbc.postgresql.org/

---------------------------------------------------------------------------

INSTALLING THE DRIVER

To install the driver, the postgresql.jar file has to be in the classpath.

ie: under LINUX/SOLARIS (the example here is my linux box):

	export CLASSPATH=.:/usr/local/pgsql/share/java/postgresql.jar

---------------------------------------------------------------------------

USING THE DRIVER

To use the driver, you must introduce it to JDBC. Again, there's two ways
of doing this:

1: Hardcoded.

   This method hardcodes your driver into your application/applet. You
   introduce the driver using the following snippet of code:

	try {
	  Class.forName("legacy.org.postgresql.Driver");
	} catch(Exception e) {
	  // your error handling code goes here
	}

   Remember, this method restricts your code to just the postgresql database.
   However, this is how most people load the driver.

2: Parameters

   This method specifies the driver from the command line. When running the
   application, you specify the driver using the option:

	-Djdbc.drivers=org.postgresql.Driver

   eg: This is an example of running one of my other projects with the driver:

	java -Djdbc.drivers=org.postgresql.Driver uk.org.retep.finder.Main

   note: This method only works with Applications (not for Applets).
	 However, the application is not tied to one driver, so if you needed
	 to switch databases (why I don't know ;-) ), you don't need to
	 recompile the application (as long as you havent hardcoded the url's).

---------------------------------------------------------------------------

JDBC URL syntax

The driver recognises JDBC URL's of the form:

	jdbc:postgresqllegacy:database

	jdbc:postgresqllegacy://host/database

	jdbc:postgresqllegacy://host:port/database

Also, you can supply both username and passwords as arguments, by appending
them to the URL. eg:

	jdbc:postgresqllegacy:database?user=me
	jdbc:postgresqllegacy:database?user=me&password=mypass

Notes:

1) If you are connecting to localhost or 127.0.0.1 you can leave it out of the
   URL. ie: jdbc:postgresqllegacy://localhost/mydb can be replaced with
   jdbc:postgresqllegacy:mydb

2) The port defaults to 5432 if it's left out.

---------------------------------------------------------------------------

That's the basics related to this driver. You'll need to read the JDBC Docs
on how to use it.
