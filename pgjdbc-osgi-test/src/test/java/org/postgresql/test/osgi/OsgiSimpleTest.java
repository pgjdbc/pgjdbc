package org.postgresql.test.osgi;

import org.postgresql.PGProperty;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.Configuration;
import org.ops4j.pax.exam.CoreOptions;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.junit.PaxExam;
import org.ops4j.pax.exam.spi.reactors.ExamReactorStrategy;
import org.ops4j.pax.exam.spi.reactors.PerMethod;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Properties;
import javax.inject.Inject;
import javax.sql.DataSource;
import javax.sql.XAConnection;
import javax.sql.XADataSource;

@RunWith(PaxExam.class)
@ExamReactorStrategy(PerMethod.class)
public class OsgiSimpleTest {

  @Inject
  org.osgi.service.jdbc.DataSourceFactory dataSourceFactory;

  @Configuration
  public Option[] config() {
    return CoreOptions.options(
        CoreOptions.mavenBundle("org.postgresql", "postgresql").versionAsInProject(),
        CoreOptions.junitBundles()
    );
  }

  @Test
  public void createDataSource() throws SQLException {
    Properties props = getProperties();
    DataSource dataSource = dataSourceFactory.createDataSource(props);
    Connection con = dataSource.getConnection();
    testConnection(con);
  }

  @Test
  public void createXADataSource() throws SQLException {
    Properties props = getProperties();
    XADataSource dataSource = dataSourceFactory.createXADataSource(props);
    XAConnection con = dataSource.getXAConnection();
    testConnection(con.getConnection());
  }

  @Test
  public void createDriver() throws SQLException {
    Properties props = getProperties();
    Driver driver = dataSourceFactory.createDriver(new Properties());
    Connection con = driver.connect(getUrl(), props);
    testConnection(con);
  }

  private void testConnection(Connection con) throws SQLException {
    Statement st = con.createStatement();
    ResultSet rs = st.executeQuery("select 2+2*2");
    rs.next();
    Assert.assertEquals("2+2*2 == 6", 6, rs.getInt(1));
    con.close();
  }

  private String getUrl() {
    Properties p = loadPropertyFiles("build.properties");

    return "jdbc:postgresql://"
        + p.get("server") + ":"
        + p.get("port") + "/"
        + p.get("database")
        + "?loglevel=" + p.get("loglevel")
        ;
  }

  private Properties getProperties() {
    Properties p = loadPropertyFiles("build.properties");
    if ("0".equals(p.getProperty("protocolVersion"))) {
      p.remove("protocolVersion");
    }
    p.putAll(System.getProperties());
    Properties p2 = new Properties();
    for (Map.Entry<Object, Object> entry : p.entrySet()) {
      if (PGProperty.forName((String) entry.getKey()) != null) {
        p2.put(entry.getKey(), entry.getValue());
      }
    }
    String user = (String) p.get("username");
    if (user != null) {
      PGProperty.USER.set(p2, user);
    }
    return p2;
  }

  public static Properties loadPropertyFiles(String... names) {
    Properties p = new Properties();
    for (String name : names) {
      for (int i = 0; i < 2; i++) {
        // load x.properties, then x.local.properties
        if (i == 1 && name.endsWith(".properties") && !name.endsWith(".local.properties")) {
          name = name.replaceAll("\\.properties$", ".local.properties");
        }
        File f = getFile(name);
        if (!f.exists()) {
          System.out.println("Configuration file " + f.getAbsolutePath()
              + " does not exist. Consider adding it to specify test db host and login");
          continue;
        }
        try {
          p.load(new FileInputStream(f));
        } catch (IOException ex) {
          // ignore
        }
      }
    }
    return p;
  }

  /**
   * Resolves file path with account of {@code build.properties.relative.path}. This is a bit tricky
   * since during maven release, maven does a temporary checkout to {@code core/target/checkout}
   * folder, so that script should somehow get {@code build.local.properties}
   *
   * @param name original name of the file, as if it was in the root pgjdbc folder
   * @return actual location of the file
   */
  public static File getFile(String name) {
    if (name == null) {
      throw new IllegalArgumentException("null file name is not expected");
    }
    if (name.startsWith("/")) {
      return new File(name);
    }
    return new File(System.getProperty("build.properties.relative.path", "../"), name);
  }
}
