/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.gss;

import org.postgresql.PGProperty;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * Thin JDBC helper used by the GSS test to provision users/databases and to open the connections
 * whose GSS authentication and encryption status is then asserted.
 */
class PgGssConnection {
  private final String host;
  private final int port;
  private final Properties properties = new Properties();

  PgGssConnection(String host, int port) {
    this.host = host;
    this.port = port;
  }

  void addProperty(PGProperty property, String value) {
    property.set(properties, value);
  }

  void addProperty(PGProperty property, boolean value) {
    property.set(properties, value);
  }

  Connection tryConnect(String database, String host, int port, String user, String password)
      throws SQLException {
    String url = "jdbc:postgresql://" + host + ":" + port + "/" + database;
    PGProperty.USER.set(properties, user);
    PGProperty.PASSWORD.set(properties, password);
    return DriverManager.getConnection(url, properties);
  }

  /**
   * Runs {@code query} (which is expected to return a single boolean column) and returns its value.
   */
  boolean select(Connection connection, String query) throws SQLException {
    try (Statement statement = connection.createStatement();
         ResultSet resultSet = statement.executeQuery(query)) {
      return resultSet.next() && resultSet.getBoolean(1);
    }
  }

  void createUser(String superuser, String superPassword, String user, String password)
      throws SQLException {
    String url = "jdbc:postgresql://" + host + ":" + port + "/postgres";
    PGProperty.USER.set(properties, superuser);
    PGProperty.PASSWORD.set(properties, superPassword);
    try (Connection connection = DriverManager.getConnection(url, properties);
         Statement statement = connection.createStatement()) {
      try (ResultSet resultSet =
               statement.executeQuery("select * from pg_user where usename = '" + user + "'")) {
        if (resultSet.next()) {
          return;
        }
      }
      statement.execute("create user " + user + " with password '" + password + "'");
    }
  }

  void createDatabase(String superuser, String superPassword, String owner, String database)
      throws SQLException {
    String url = "jdbc:postgresql://" + host + ":" + port + "/postgres";
    PGProperty.USER.set(properties, superuser);
    PGProperty.PASSWORD.set(properties, superPassword);
    try (Connection connection = DriverManager.getConnection(url, properties);
         Statement statement = connection.createStatement()) {
      try (ResultSet resultSet =
               statement.executeQuery("select * from pg_database where datname = '" + database + "'")) {
        if (resultSet.next()) {
          return;
        }
      }
      statement.execute("create database " + database + " owner '" + owner + "'");
    }
  }
}
