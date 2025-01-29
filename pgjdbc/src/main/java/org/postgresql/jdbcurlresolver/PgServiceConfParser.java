/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbcurlresolver;

import org.postgresql.PGEnvironment;
import org.postgresql.PGProperty;
import org.postgresql.util.OSUtil;
import org.postgresql.util.internal.FileUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * helps to read Connection Service File.
 * https://www.postgresql.org/docs/current/libpq-pgservice.html
 */
class PgServiceConfParser {

  private static final Logger LOGGER = Logger.getLogger(PgServiceConfParser.class.getName());
  private final String serviceName;

  private PgServiceConfParser(String serviceName) {
    this.serviceName = serviceName;
  }

  /**
   * Read pg_service.conf resource
   *
   * @param serviceName service name to search for
   * @return key value pairs
   */
  static Properties getServiceProperties(String serviceName) throws JdbcUrlResolverFatalException {
    PgServiceConfParser pgServiceConfParser = new PgServiceConfParser(serviceName);
    return pgServiceConfParser.findServiceDescription();
  }

  private Properties findServiceDescription() throws JdbcUrlResolverFatalException {
    String resourceName = findPgServiceConfResourceName();
    //
    Properties result;
    try (InputStream inputStream = openInputStream(resourceName)) {
      result = parseInputStream(inputStream);
    } catch (IOException e) {
      String message = String.format( "Failed to handle resource [%s] with error [%s]", resourceName, e.getMessage());
      LOGGER.log(Level.SEVERE, message);
      throw new JdbcUrlResolverFatalException(message);
    }
    //
    return result;
  }

  // open URL or File
  private static InputStream openInputStream(String resourceName) throws IOException {

    try {
      URL url = new URL(resourceName);
      return url.openStream();
    } catch ( MalformedURLException ex ) {
      // try file
      return FileUtils.newBufferedInputStream(resourceName);
    }
  }

  // choose resource where to search for service description
  private static String findPgServiceConfResourceName() throws JdbcUrlResolverFatalException {
    // if there is value, use it - 1st priority
    {
      String propertyName = PGEnvironment.ORG_POSTGRESQL_PGSERVICEFILE.getName();
      String resourceName = PGEnvironment.ORG_POSTGRESQL_PGSERVICEFILE.readStringValue();
      if (resourceName != null && !resourceName.trim().isEmpty()) {
        LOGGER.log(Level.FINE, "Value [{0}] selected from property [{1}]",
            new Object[]{resourceName, propertyName});
        return resourceName;
      }
    }

    // if there is value, use it - 2nd priority
    {
      String envVariableName = PGEnvironment.PGSERVICEFILE.getName();
      String resourceName = PGEnvironment.PGSERVICEFILE.readStringValue();
      if (resourceName != null && !resourceName.trim().isEmpty()) {
        LOGGER.log(Level.FINE, "Value [{0}] selected from environment variable [{1}]",
            new Object[]{resourceName, envVariableName});
        return resourceName;
      }
    }

    // if there is readable file in default location, use it - 3rd priority
    {
      // default file name
      String defaultPgServiceFilename = OSUtil.getDefaultPgServiceFilename();
      File resourceFile = new File(defaultPgServiceFilename);
      if (resourceFile.canRead()) {
        LOGGER.log(Level.FINE, "Value [{0}] selected because file exist in user home directory", new Object[]{resourceFile.getAbsolutePath()});
        return resourceFile.getAbsolutePath();
      } else {
        LOGGER.log(Level.FINE, "Default .pg_service.conf file [{0}] not found", new Object[]{resourceFile.getAbsolutePath()});
      }
    }

    // if there is value, use it - 4th priority
    {
      String envVariableName = PGEnvironment.PGSYSCONFDIR.getName();
      String pgSysconfDir = PGEnvironment.PGSYSCONFDIR.readStringValue();
      if (pgSysconfDir != null && !pgSysconfDir.trim().isEmpty()) {
        String resourceName = OSUtil.getDefaultPgServiceFilename(pgSysconfDir);
        LOGGER.log(Level.FINE, "Value [{0}] selected using environment variable [{1}]", new Object[]{resourceName, envVariableName});
        return resourceName;
      }
    }

    // otherwise fail
    String message = "Resource file [.pg_service.conf] not found";
    LOGGER.log(Level.SEVERE, message);
    throw new JdbcUrlResolverFatalException(message);
  }

  /*
  # Requirements for stream handling (have to match with libpq behaviour)
  #
  # space around line is removed
  #   Line: "   host=my-host    "
  #   equal to : "host=my-host"
  # keys are case sensitive
  #   Line: "host=my-host"
  #   not equal to : "HOST=my-host"
  # keys are limited with values described in enum PGProperty field name
  #   key is invalid: "my-host=my-host"
  # unexpected keys produce error
  #   Example: "my-host=my-host"
  #   Example: "HOST=my-host"
  # space before equal sign becomes part of key
  #   Line: "host =my-host"
  #   key equals: "host "
  # space after equal sign becomes part of value
  #   Line: "host= my-host"
  #   key equals: " my-host"
  # in case of duplicate section - first entry counts
  #   Line: "[service-one]"
  #   Line: "host=host-one"
  #   Line: "[service-one]"
  #   Line: "host=host-two"
  #   --> host-one is selected
  # in case of duplicate key - first entry counts
  #   Line: "[service-one]"
  #   Line: "host=host-one"
  #   Line: "host=host-two"
  #   --> host-one is selected
  # service name is case sensitive
  #   Line: "[service-one]"
  #   Line: "[service-ONE]"
  #   --> these are unique service names
  # whatever is between brackets is considered as service name (including space)
  #   Line: "[ service-ONE]"
  #   Line: "[service-ONE ]"
  #   Line: "[service ONE]"
  #   --> these are unique service names
  */
  @SuppressWarnings("RedundantControlFlow")
  private Properties parseInputStream(InputStream inputStream) throws IOException, JdbcUrlResolverFatalException {
    Properties result = new Properties();
    boolean isFound = false;
    try (
        Reader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
        BufferedReader br = new BufferedReader(reader)) {
      //
      String originalLine;
      String line;
      int lineNumber = 0;
      while ((originalLine = br.readLine()) != null) {
        lineNumber++;
        // remove spaces around it
        line = originalLine.trim();
        // skip if empty line or starts with comment sign
        if (line.isEmpty() || line.startsWith("#")) {
          continue;
        }
        // find first equal sign
        int indexOfEqualSign = line.indexOf("=");
        // is it section start?
        if (line.startsWith("[") && line.endsWith("]")) {
          // stop processing if section with correct name was found already
          if (isFound) {
            break;
          }
          // get name of section
          String sectionName = line.substring(1, line.length() - 1);
          // if match then mark it as section is found
          if (serviceName.equals(sectionName)) {
            isFound = true;
          }
        } else if (!isFound) {
          // skip further processing until section is found
          // TODO: avoid continue here to resolve https://errorprone.info/bugpattern/RedundantControlFlow
          //noinspection UnnecessaryContinue
          continue;
        } else if (indexOfEqualSign > 1) {
          // get key and value
          String key = line.substring(0, indexOfEqualSign);
          String value = line.substring(indexOfEqualSign + 1);
          // check key against set of allowed keys
          PGProperty pgProperty = PGProperty.forName(key);
          if (pgProperty == null) {
            // log list of allowed keys
            String allowedValuesCommaSeparated = Arrays.stream(PGProperty.values()).map(PGProperty::getName)
                .sorted().collect(Collectors.joining(","));
            String message = String.format("Got invalid key: line number [%s], value [%s], allowed values [%s]",
                lineNumber, originalLine, allowedValuesCommaSeparated);
            LOGGER.log(Level.INFO, message);
            // fail here because of invalid key
            message = String.format("Got invalid key: line number [%s], value [%s]", lineNumber, originalLine);
            LOGGER.log(Level.SEVERE, message);
            throw new JdbcUrlResolverFatalException(message);
          } else if (pgProperty == PGProperty.SERVICE) {
            String message = String.format("key 'service' is not allowed: line number [%s], value [%s]", lineNumber, originalLine);
            // fail here because recursive "service" value processing is not supported
            LOGGER.log(Level.SEVERE, message);
            throw new JdbcUrlResolverFatalException(message);
          }
          // ignore line if value is missing
          if (!value.isEmpty()) {
            // ignore line having duplicate key, otherwise store key-value pair
            if (!pgProperty.isPresent(result)) {
              pgProperty.set(result, value);
            }
          }
        } else {
          String message = String.format("Not valid line: line number [%s], value [%s]", lineNumber, originalLine);
          // if not equal sign then stop processing because of invalid syntax
          LOGGER.log(Level.SEVERE, message);
          throw new JdbcUrlResolverFatalException(message);
        }
      }
    }
    // fail if service section not found
    if (isFound) {
      return result;
    } else {
      String message = String.format("Definition of service [%s] not found", serviceName);
      LOGGER.log(Level.SEVERE, message);
      throw new JdbcUrlResolverFatalException(message);
    }
  }

}
