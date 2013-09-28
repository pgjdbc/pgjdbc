This sub directory includes SSL related tests.

To setup the tests build the driver as usual (e.g. `ant jar`) then do the following:

* Initialize the virtual machine that the tests will be run against (this will take a few minutes the first time):

        $ vagrant up

* Change directory to this `ssl-test` directory

        $ cd ssl-test

* Build and run the tests:

        $ mvn clean compile test

    For the environment variable and system property tests to successfully execute you will need to specify them on the command line. Otherwise there will be two test failures complaining that the SSL socket factory could not be created. Specify them and run the tests successfully like this:

        $ SERVER_CERT="$(cat src/test/resources/server.crt)" mvn clean compile test -Dpostgresql.server.crt="$(cat src/test/resources/server.crt)"

**Note:** This SSL test Maven project refers to your locally built PG-JDBC driver via a system scoped Maven dependency (referencing `"${basedir}/../jars/posgresql"`). If the build process for the parent is "Mavenized" then this won't be necessary.
