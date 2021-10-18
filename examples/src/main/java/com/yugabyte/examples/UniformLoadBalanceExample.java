// Copyright (c) YugaByte, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software distributed under the License
// is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
// or implied.  See the License for the specific language governing permissions and limitations
// under the License.
//

package com.yugabyte.examples;

import com.yugabyte.ysql.ClusterAwareLoadBalancer;
import com.yugabyte.ysql.LoadBalanceProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.sql.*;
import java.util.*;

public class UniformLoadBalanceExample {
  protected static String controlUrl = "";
  protected static HikariDataSource hikariDataSource;
  protected static Scanner scanner = new Scanner(System.in);
  protected static List<Connection> connectionsCreatedFroamApi = new ArrayList<>();
  protected static boolean debugLogging = false;

  public static void main(String[] args) {
    int argsLen = args.length;
    Boolean verbose = argsLen > 0 ? args[0].equals("1") : Boolean.FALSE;
    Boolean interactive = argsLen > 1 ? args[1].equals("1") : Boolean.FALSE;
    debugLogging = argsLen > 2 ? args[2].equals("1") : Boolean.FALSE;
    // Since it is just a demo app. Using some smaller values so that it can run
    // on laptop
    String numConnections = "6";
    String controlHost = "127.0.0.1";
    String controlPort = "5433";

    if (debugLogging) {
      controlUrl = "jdbc:yugabytedb://" + controlHost
        + ":" + controlPort + "/yugabyte?user=yugabyte&password=yugabyte&load-balance=true&loggerLevel=debug";
    } else {
      controlUrl = "jdbc:yugabytedb://" + controlHost
        + ":" + controlPort + "/yugabyte?user=yugabyte&password=yugabyte&load-balance=true";
    }

    System.out.println("Setting up the connection pool having 6 connections.......");

    testUsingHikariPool("uniform_load_balance", "true", "simple",
      controlHost, controlPort, numConnections, verbose, interactive);
  }

  protected static void testUsingHikariPool(String poolName, String lbpropvalue, String lookupKey,
      String hostName, String port, String numConnections, Boolean verbose, Boolean interactive) {
    try {
      String ds_yb = "com.yugabyte.ysql.YBClusterAwareDataSource";

      //This is just for demo purpose because right now default time for refresh is 5min
      //and we don't want the user to wait that much in this app
      ClusterAwareLoadBalancer.forceRefresh = true;

      Properties poolProperties = new Properties();
      if (debugLogging) {
        poolProperties.setProperty("dataSource.loggerLevel", "DEBUG");
      }
      poolProperties.setProperty("poolName", poolName);
      poolProperties.setProperty("dataSourceClassName", ds_yb);
      poolProperties.setProperty("maximumPoolSize", numConnections);
      poolProperties.setProperty("connectionTimeout", "1000000");
      poolProperties.setProperty("autoCommit", "true");
      poolProperties.setProperty("dataSource.serverName", hostName);
      poolProperties.setProperty("dataSource.portNumber", port);
      poolProperties.setProperty("dataSource.databaseName", "yugabyte");
      poolProperties.setProperty("dataSource.user", "yugabyte");
      poolProperties.setProperty("dataSource.password", "yugabyte");
      // poolProperties.setProperty("dataSource.loadBalance", "true");
      poolProperties.setProperty("dataSource.additionalEndpoints",
        "127.0.0.2:5433,127.0.0.3:5433");
      if (!lbpropvalue.equals("true")) {
        poolProperties.setProperty("dataSource.topologyKeys", lookupKey);
      }

      HikariConfig hikariConfig = new HikariConfig(poolProperties);
      hikariConfig.validate();
      hikariDataSource = new HikariDataSource(hikariConfig);

      //creating a table
      try (Connection connection = hikariDataSource.getConnection()) {
        performTableCreation(connection);
      }

      //running multiple threads concurrently
      runSqlQueriesOnMultipleThreads();

      // This is an internal map for debugging which keeps a map of
      // "server -> num_connections"
      // made by the driver in this application
      LoadBalanceProperties.CONNECTION_MANAGER_MAP.get(lookupKey).printHostToConnMap();
      System.out.println();

      if (interactive) {
        System.out.println("You can verify the connections on the server side using your browser");
        System.out.println("For example, you can visit \"127.0.0.1:13000/rpcz\"" + " and similarly for others...");
      }

      continueScript("add_node");

      // This will pause this java app till adding a node is done in cluster by shell script,
      // This is for the interactive mode.
      pauseApp(".jdbc_example_app_checker");

      makeSomeNewConnections(7);

      // Printing the current load from internal accounting map
      LoadBalanceProperties.CONNECTION_MANAGER_MAP.get(lookupKey).printHostToConnMap();
      System.out.println();

      if (interactive) {
        System.out.println("You can verify the connections on the server side using your browser");
        System.out.println("For example, you can visit \"127.0.0.1:13000/rpcz\"" + " and similarly for others...");
      }

      continueScript("stop_node");

      //This will pause this java app till stopping a node is done in cluster by shell script.
      // and for interactive-mode if required based on the options provided while executing the script
      pauseApp(".jdbc_example_app_checker2");

      makeSomeNewConnections(4);

      // Printing the current load from internal accounting map
      LoadBalanceProperties.CONNECTION_MANAGER_MAP.get(lookupKey).printHostToConnMap();
      System.out.println();

      if (interactive) {
        System.out.println("You can verify the connections on the server side using your browser");
        System.out.println("For example, you can visit \"127.0.0.1:13000/rpcz\"" + " and similarly for others...");
      }

      continueScript("perform_cleanup");

      //This will pause this java app for interactive-mode if required based on the options provided while executing the script
      pauseApp(".jdbc_example_app_checker3");
      System.out.println("Closing the java app...");
    } catch (SQLException throwables) {
      throwables.printStackTrace();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      e.printStackTrace();
    } finally {
      //closing the resources
      if (hikariDataSource != null) {
        hikariDataSource.close();
      }
      for (Connection connection : connectionsCreatedFroamApi) {
        try {
          connection.close();
        } catch (SQLException exception) {
          exception.printStackTrace();
        }
      }
    }
  }

  protected static void performTableCreation(Connection connection) {
    try (Statement statement = connection.createStatement()) {
      statement.execute("DROP TABLE IF EXISTS AGENTS");
      String query = "CREATE TABLE AGENTS  ( AGENT_CODE VARCHAR(6) NOT NULL PRIMARY KEY, AGENT_NAME VARCHAR(40), " +
        "WORKING_AREA VARCHAR(35), COMMISSION numeric(10,2), PHONE_NO VARCHAR(15))";
      statement.execute(query);

      statement.executeUpdate("INSERT INTO AGENTS VALUES ('A007', 'Ramasundar', 'Bangalore', '0.15', '077-25814763')");
      statement.executeUpdate("INSERT INTO AGENTS VALUES ('A003', 'Alex ', 'London', '0.13', '075-12458969')");
      statement.executeUpdate("INSERT INTO AGENTS VALUES ('A008', 'Alford', 'New York', '0.12', '044-25874365')");
      statement.executeUpdate("INSERT INTO AGENTS VALUES ('A011', 'Ravi Kumar', 'Bangalore', '0.15', '077-45625874')");
      statement.executeUpdate("INSERT INTO AGENTS VALUES ('A010', 'Santakumar', 'Chennai', '0.14', '007-22388644')");
      statement.executeUpdate("INSERT INTO AGENTS VALUES ('A012', 'Lucida', 'San Jose', '0.12', '044-52981425')");
      statement.executeUpdate("INSERT INTO AGENTS VALUES ('A005', 'Anderson', 'Brisban', '0.13', '045-21447739' )");
      statement.executeUpdate("INSERT INTO AGENTS VALUES ('A001', 'Subbarao', 'Bangalore', '0.14', '077-12346674')");
    } catch (SQLException throwables) {
      throwables.printStackTrace();
    }
  }

  protected static void runSqlQueriesOnMultipleThreads() throws InterruptedException {
    int nthreads = 6;
    Thread[] threads = new Thread[nthreads];
    for (int i = 0; i < nthreads; i++) {
      threads[i] = new Thread(new UniformLoadBalanceExample.ConcurrentQueriesClass());
    }

    System.out.println("Waiting for multiple concurrent threads to execute some SQL queries...");
    for (int i = 0; i < nthreads; i++) {
      threads[i].start();
    }

    for (int i = 0; i < nthreads; i++) {
      threads[i].join();
    }
  }

  protected static String[] sqlQueries = new String[]{
    "Select AGENT_NAME, COMMISSION from AGENTS",
    "Select max(COMMISSION) from AGENTS",
    "Select PHONE_NO from AGENTS",
    "Select WORKING_AREA from AGENTS"
  };

  protected static void runSomeSqlQueries(Connection connection) {
    for (int i = 0; i < sqlQueries.length; i++) {
      try (Statement statement = connection.createStatement();
           ResultSet rs = statement.executeQuery(sqlQueries[i])) {
        int cnt = 0;
        while (rs.next()) {
          cnt += 1;
        }
      } catch (SQLException exception) {
        exception.printStackTrace();
      }
    }
  }

  static class ConcurrentQueriesClass implements Runnable {
    @Override
    public void run() {
      try (Connection connection = hikariDataSource.getConnection()) {
        for (int i = 1; i <= 1000; i++) {
          runSomeSqlQueries(connection);
        }
      } catch (SQLException throwables) {
        throwables.printStackTrace();
      }
    }
  }


  protected static void makeSomeNewConnections(int new_connections) {
    System.out.println("Creating " + new_connections + " new connections.... with controlUrl: " + controlUrl);
    try {
      for (int i = 1; i <= new_connections; i++) {
        Connection connection = DriverManager.getConnection(controlUrl);
        runSomeSqlQueries(connection);
        connectionsCreatedFroamApi.add(connection);
      }
    } catch (SQLException throwables) {
      throwables.printStackTrace();
    }
  }

  protected static void continueScript(String flagValue) {
    try (FileWriter fileWriter = new FileWriter(".notify_shell_script")) {
      fileWriter.write(flagValue);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  protected static void pauseApp(String fileName) {
    File file = new File(fileName);
    while (file.exists()==false) {
      try {
        Thread.sleep(2000);
      } catch (InterruptedException e) {
        // ignore the exception and break
        Thread.currentThread().interrupt();
        break;
      }
    }
  }
}
