/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import org.postgresql.Driver;
import org.postgresql.PGProperty;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public class PropertyLoader {
  private static final Logger LOGGER = Logger.getLogger("org.postgresql.util.PropertyLogger");
  private static final String DEFAULT_PORT =
    /*$"\""+mvn.project.property.template.default.pg.port+"\";"$*//*-*/"5431";

  // Pure pgJDBC-style property loading. It's more an internal method, the
  // Driver.connect() should be used instead.
  public static Properties oldStyleLoad(String url, Properties info) throws PSQLException {
    Properties defaults;
    PropertyLoader loader = new PropertyLoader();
    defaults = loader.getDefaultProperties();
    Properties props = new Properties(defaults);
    overrideProperties(props, info);
    Properties urlProps = Driver.parseURL(url, new Properties());
    if (urlProps == null) {
      return null;
    }
    overrideProperties(props, urlProps);
    return props;
  }

  // Helper to retrieve default properties from classloader resource
  // properties files.
  private Properties defaultProperties;

  private synchronized Properties getDefaultProperties() throws PSQLException {
    if (defaultProperties != null) {
      return defaultProperties;
    }

    // Make sure we load properties with the maximum possible privileges.
    try {
      defaultProperties =
          AccessController.doPrivileged(new PrivilegedExceptionAction<Properties>() {
            public Properties run() throws IOException {
              return loadDefaultProperties();
            }
          });
    } catch (PrivilegedActionException e) {
      throw new PSQLException(GT.tr("Error loading default settings from driverconfig.properties"),
                              PSQLState.UNEXPECTED_ERROR, (IOException) e.getException());
    }

    return defaultProperties;
  }

  private Properties loadDefaultProperties() throws IOException {
    Properties merged = new Properties();

    try {
      PGProperty.USER.set(merged, System.getProperty("user.name"));
    } catch (SecurityException se) {
      // We're just trying to set a default, so if we can't
      // it's not a big deal.
    }

    // If we are loaded by the bootstrap classloader, getClassLoader()
    // may return null. In that case, try to fall back to the system
    // classloader.
    //
    // We should not need to catch SecurityException here as we are
    // accessing either our own classloader, or the system classloader
    // when our classloader is null. The ClassLoader javadoc claims
    // neither case can throw SecurityException.
    ClassLoader cl = getClass().getClassLoader();
    if (cl == null) {
      LOGGER.log(Level.FINE, "Can't find our classloader for the Driver; "
          + "attempt to use the system class loader");
      cl = ClassLoader.getSystemClassLoader();
    }

    if (cl == null) {
      LOGGER.log(Level.WARNING, "Can't find a classloader for the Driver; not loading driver "
          + "configuration from org/postgresql/driverconfig.properties");
      return merged; // Give up on finding defaults.
    }

    LOGGER.log(Level.FINE, "Loading driver configuration via classloader {0}", cl);

    // When loading the driver config files we don't want settings found
    // in later files in the classpath to override settings specified in
    // earlier files. To do this we've got to read the returned
    // Enumeration into temporary storage.
    ArrayList<URL> urls = new ArrayList<URL>();
    Enumeration<URL> urlEnum = cl.getResources("org/postgresql/driverconfig.properties");
    while (urlEnum.hasMoreElements()) {
      urls.add(urlEnum.nextElement());
    }

    for (int i = urls.size() - 1; i >= 0; i--) {
      URL url = urls.get(i);
      LOGGER.log(Level.FINE, "Loading driver configuration from: {0}", url);
      InputStream is = url.openStream();
      merged.load(is);
      is.close();
    }

    return merged;
  }

  public static void overrideProperties(Properties props, Properties info) throws PSQLException {
    if (info == null) {
      return;
    }

    Set<String> e = info.stringPropertyNames();
    for (String propName : e) {
      String propValue = info.getProperty(propName);
      if (propValue == null) {
        throw new PSQLException(GT.tr("Properties for the driver contains a non-string value for the key ")
                                + propName,
                                PSQLState.UNEXPECTED_ERROR);
      }
      props.setProperty(propName, propValue);
    }
  }

  public static Properties parseURL(String url) {
    Properties urlProps = new Properties();

    String urlServer = url;
    String urlArgs = "";

    int qPos = url.indexOf('?');
    if (qPos != -1) {
      urlServer = url.substring(0, qPos);
      urlArgs = url.substring(qPos + 1);
    }

    if (!urlServer.startsWith("jdbc:postgresql:")) {
      LOGGER.log(Level.FINE, "JDBC URL must start with \"jdbc:postgresql:\" but was: {0}", url);
      return null;
    }
    urlServer = urlServer.substring("jdbc:postgresql:".length());

    if (urlServer.startsWith("//")) {
      urlServer = urlServer.substring(2);
      int slash = urlServer.indexOf('/');
      if (slash == -1) {
        LOGGER.log(Level.WARNING, "JDBC URL must contain a / at the end of the host or port: {0}", url);
        return null;
      }
      urlProps.setProperty("PGDBNAME", URLCoder.decode(urlServer.substring(slash + 1)));

      String[] addresses = urlServer.substring(0, slash).split(",");
      StringBuilder hosts = new StringBuilder();
      StringBuilder ports = new StringBuilder();
      for (String address : addresses) {
        int portIdx = address.lastIndexOf(':');
        if (portIdx != -1 && address.lastIndexOf(']') < portIdx) {
          String portStr = address.substring(portIdx + 1);
          try {
            int port = Integer.parseInt(portStr);
            if (port < 1 || port > 65535) {
              LOGGER.log(Level.WARNING, "JDBC URL port: {0} not valid (1:65535) ", portStr);
              return null;
            }
          } catch (NumberFormatException ignore) {
            LOGGER.log(Level.WARNING, "JDBC URL invalid port number: {0}", portStr);
            return null;
          }
          ports.append(portStr);
          hosts.append(address.subSequence(0, portIdx));
        } else {
          ports.append(DEFAULT_PORT);
          hosts.append(address);
        }
        ports.append(',');
        hosts.append(',');
      }
      ports.setLength(ports.length() - 1);
      hosts.setLength(hosts.length() - 1);
      urlProps.setProperty("PGPORT", ports.toString());
      urlProps.setProperty("PGHOST", hosts.toString());
    } else if (urlServer.length() > 0) {
      urlProps.setProperty("PGDBNAME", URLCoder.decode(urlServer));
    }

    // parse the args part of the url
    String[] args = urlArgs.split("&");
    for (String token : args) {
      if (token.isEmpty()) {
        continue;
      }
      int pos = token.indexOf('=');
      if (pos == -1) {
        urlProps.setProperty(token, "");
      } else {
        urlProps.setProperty(token.substring(0, pos), URLCoder.decode(token.substring(pos + 1)));
      }
    }

    return urlProps;
  }
}
