/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.compat;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Loads a released driver jar in an isolated class loader so its {@code org.postgresql.*} classes never
 * touch the current driver on the application classpath.
 *
 * <p>The parent is the platform class loader, not the application loader: {@code java.sql.*} resolves from
 * the JDK (shared with the current driver), while the baseline's {@code org.postgresql.*} comes only from
 * the jar. Both driver versions therefore reach the caller through the same {@code java.sql} interfaces
 * without a package clash. The platform loader is obtained as the application loader's parent (rather than
 * {@code getPlatformClassLoader()}) so this class still compiles at Java 8 source level.
 *
 * <p>Connections are opened by calling {@link Driver#connect} on the explicit baseline instance, not
 * through {@link java.sql.DriverManager}. Both drivers accept {@code jdbc:postgresql:} and register
 * themselves on class init, so letting DriverManager pick would non-deterministically return either one.
 */
public final class LegacyDriverLoader implements AutoCloseable {
  private final URLClassLoader loader;
  private final Driver driver;

  public LegacyDriverLoader(Path jar) throws IOException, ReflectiveOperationException {
    URL url = jar.toUri().toURL();
    this.loader = new URLClassLoader(new URL[]{url}, platformClassLoader());
    Class<?> driverClass = loader.loadClass("org.postgresql.Driver");
    this.driver = (Driver) driverClass.getDeclaredConstructor().newInstance();
  }

  /**
   * The platform class loader on a modular JDK (9+), reached as the application class loader's parent.
   * It carries {@code java.sql.*} but not the current driver, so the baseline's {@code org.postgresql.*}
   * resolves from its jar alone. A {@code null} result (bootstrap loader) is acceptable: on Java 8
   * {@code java.sql} lives on the bootstrap loader anyway.
   */
  private static @Nullable ClassLoader platformClassLoader() {
    return ClassLoader.getSystemClassLoader().getParent();
  }

  /**
   * Opens a connection through the baseline driver.
   *
   * @param url a {@code jdbc:postgresql:} URL
   * @param props connection properties
   * @return a live connection reached through the shared {@link Connection} interface
   * @throws SQLException if the driver rejects the URL or fails to connect
   */
  public Connection connect(String url, Properties props) throws SQLException {
    Connection connection = driver.connect(url, props);
    if (connection == null) {
      throw new SQLException("Baseline driver did not accept the URL: " + url);
    }
    return connection;
  }

  /** Returns the baseline driver version as reported by the jar, e.g. {@code 42.7}. */
  public String version() {
    return driver.getMajorVersion() + "." + driver.getMinorVersion();
  }

  /**
   * The isolated class loader that holds the baseline's classes. Use it to build a baseline-side {@code
   * org.postgresql.*} input (for example a {@code PGobject}) so it can be bound through the baseline
   * driver; an object from the current loader would fail a cross-loader cast inside the baseline.
   */
  public ClassLoader classLoader() {
    return loader;
  }

  @Override
  public void close() throws IOException {
    loader.close();
  }
}
