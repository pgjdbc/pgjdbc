# YugabyteDB JDBC Driver
This is a distributed JDBC driver for YugabyteDB SQL. This driver is based on the [PostgreSQL JDBC Driver](https://github.com/pgjdbc/pgjdbc).

## Features

This JDBC driver has the following features:

### Cluster Awareness to eliminate need for a load balancer

This driver adds a `YBClusterAwareDataSource` that requires only an initial _contact point_ for the YugabyteDB cluster, using which it discovers the rest of the nodes. Additionally, it automatically learns about the nodes being started/added or stopped/removed. Internally the driver keeps track of number of connections it has created to each server endpoint and every new connection request is connected to the least loaded server as per the driver's view.

### Topology Awareness to enable geo-distributed apps

This is similar to 'Cluster Awareness' but uses those servers which are part of a given set of geo-locations specified by _topology-keys_.

### Shard awareness for high performance

> **NOTE:** This feature is still in the design phase.

### Connection Properties added for load balancing

- _load-balance_   - It expects **true/false** as its possible values. In YBClusterAwareDataSource load balancing is true by default. However when using the DriverManager.getConnection() API the 'load-balance' property needs to be set to 'true'.
- _topology-keys_  - It takes a comma separated geo-location values. The geo-location can be given as 'cloud:region:zone'.

Please refer to the [Use the Driver](#Use the Driver) section for examples.

### Get the Driver

### From Maven

Either add the following lines to your maven project in pom.xml file.
```
<dependency>
  <groupId>com.yugabyte</groupId>
  <artifactId>jdbc-yugabytedb</artifactId>
  <version>42.3.0</version>
</dependency>
```

or you can visit to this link for the latest version of dependency: https://search.maven.org/artifact/com.yugabyte/jdbc-yugabytedb

### Build locally

0. Build environment

   gpgsuite needs to be present on the machine where build is performed.
   ```
   https://gpgtools.org/
   ```
   Please install gpg and create a key.

1. Clone this repository.

    ```
    git clone https://github.com/yugabyte/pgjdbc.git && cd pgjdbc
    ```

2. Build and install into your local maven folder.

    ```
     ./gradlew publishToMavenLocal -x test -x checkstyleMain
    ```

3. Finally, use it by adding the lines below to your project.

    ```xml
    <dependency>
        <groupId>com.yugabyte</groupId>
        <artifactId>jdbc-yugabytedb</artifactId>
        <version>42.3.0</version>
    </dependency> 
    ```
> **Note:** You need to have installed 2.7.2.0-b0 or above version of YugabyteDB on your system for load balancing to work.

## Use the Driver

- Passing new connection properties for load balancing in connection url or properties bag

  For uniform load balancing across all the server you just need to specify the _load-balance=true_ property in the url.
    ```
    String yburl = "jdbc:yugabytedb://127.0.0.1:5433/yugabyte?user=yugabyte&password=yugabyte&load-balance=true";
    DriverManager.getConnection(yburl);
    ```

  For specifying topology keys you need to set the additional property with a valid comma separated value, for example _topology-keys=region1.zone1,region1.zone2_.

    ```
    String yburl = "jdbc:yugabytedb://127.0.0.1:5433/yugabyte?user=yugabyte&password=yugabyte&load-balance=true&topology-keys=region1.zone1,region1.zone2";
    DriverManager.getConnection(yburl);
    ```

- Create and setup the DataSource for uniform load balancing
  A datasource for Yugabyte has been added. It can be configured like this for load balancing behaviour.
    ```
    String jdbcUrl = "jdbc:yugabytedb://127.0.0.1:5433/yugabyte";
    YBClusterAwareDataSource ds = new YBClusterAwareDataSource();
    ds.setUrl(jdbcUrl);
    // If topology aware distribution to be enabled then
    ds.setTopologyKeys("cloud1.region1.zone1,cloud1.region2.zone2");
    // If you want to provide more endpoints to safeguard against even first connection failure due
    to the possible unavailability of initial contact point.
    ds.setAdditionalEndpoints("127.0.0.2:5433,127.0.0.3:5433");

    Connection conn = ds.getConnection();
    ```

- Create and setup the DataSource with a popular pooling solution like Hikari

    ```
    Properties poolProperties = new Properties();
    poolProperties.setProperty("dataSourceClassName", "com.yugabyte.ysql.YBClusterAwareDataSource");
    poolProperties.setProperty("maximumPoolSize", 10);
    poolProperties.setProperty("dataSource.serverName", "127.0.0.1");
    poolProperties.setProperty("dataSource.portNumber", "5433");
    poolProperties.setProperty("dataSource.databaseName", "yugabyte");
    poolProperties.setProperty("dataSource.user", "yugabyte");
    poolProperties.setProperty("dataSource.password", "yugabyte");
    // If you want to provide additional end points
    String additionalEndpoints = "127.0.0.2:5433,127.0.0.3:5433,127.0.0.4:5433,127.0.0.5:5433";
    poolProperties.setProperty("dataSource.additionalEndpoints", additionalEndpoints);
    // If you want to load balance between specific geo locations using topology keys
    String geoLocations = "region1.zone1,region2.zone2";
    poolProperties.setProperty("dataSource.topologyKeys", geoLocations);

    poolProperties.setProperty("poolName", name);

    HikariConfig config = new HikariConfig(poolProperties);
    config.validate();
    HikariDataSource ds = new HikariDataSource(config);

    Connection conn = ds.getConnection();
    ```
