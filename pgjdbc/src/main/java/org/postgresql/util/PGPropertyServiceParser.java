/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import org.postgresql.PGEnvironment;
import org.postgresql.PGProperty;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * helps to read Connection Service File.
 * https://www.postgresql.org/docs/current/libpq-pgservice.html
 */
public class PGPropertyServiceParser {

  private static final Logger LOGGER = Logger.getLogger(PGPropertyServiceParser.class.getName());
  private final String serviceName;
  private boolean ignoreIfOpenFails = true;

  private PGPropertyServiceParser(String serviceName) {
    this.serviceName = serviceName;
  }

  /**
   * Read pg_service.conf resource
   *
   * @param serviceName service name to search for
   * @return key value pairs
   */
  public static @Nullable Properties getServiceProperties(String serviceName) {
    PGPropertyServiceParser pgPropertyServiceParser = new PGPropertyServiceParser(serviceName);
    return pgPropertyServiceParser.findServiceDescription();
  }

  private @Nullable Properties findServiceDescription() {
    String resourceName = findPgServiceConfResourceName();
    if (resourceName == null) {
      return null;
    }
    //
    Properties result = null;
    try (InputStream inputStream = openInputStream(resourceName)) {
      result = parseInputStream(inputStream);
    } catch (IOException e) {
      Level level = ignoreIfOpenFails ? Level.FINE : Level.WARNING;
      LOGGER.log(level, "Failed to handle resource [{0}] with error [{1}]", new Object[]{resourceName, e.getMessage()});
    }
    //
    return result;
  }

  // open URL or File
  private InputStream openInputStream(String resourceName) throws IOException {

    try {
      URL url = new URL(resourceName);
      return url.openStream();
    } catch ( MalformedURLException ex ) {
      // try file
      File file = new File(resourceName);
      return new FileInputStream(file);
    }
  }

  // choose resource where to search for service description
  private @Nullable String findPgServiceConfResourceName() {
    // default file name
    String pgServceConfFileDefaultName = PGEnvironment.PGSERVICEFILE.getDefaultValue();

    // if there is value, use it - 1st priority
    {
      String propertyName = PGEnvironment.ORG_POSTGRESQL_PGSERVICEFILE.getName();
      String resourceName = System.getProperty(propertyName);
      if (resourceName != null && !resourceName.trim().isEmpty()) {
        this.ignoreIfOpenFails = false;
        LOGGER.log(Level.FINE, "Value [{0}] selected from property [{1}]",
            new Object[]{resourceName, propertyName});
        return resourceName;
      }
    }

    // if there is value, use it - 2nd priority
    {
      String envVariableName = PGEnvironment.PGSERVICEFILE.getName();
      String resourceName = System.getenv().get(envVariableName);
      if (resourceName != null && !resourceName.trim().isEmpty()) {
        this.ignoreIfOpenFails = false;
        LOGGER.log(Level.FINE, "Value [{0}] selected from environment variable [{1}]",
            new Object[]{resourceName, envVariableName});
        return resourceName;
      }
    }

    /*
     if file in user home is readable, use it, otherwise continue - 3rd priority
     in the case that the file is in the user home directory it is prepended with '.'
     */
    {
      String resourceName = "." + pgServceConfFileDefaultName;
      File resourceFile = new File(OSUtil.getUserConfigRootDirectory(), resourceName);
      if (resourceFile.canRead()) {
        LOGGER.log(Level.FINE, "Value [{0}] selected because file exist in user home directory", new Object[]{resourceFile.getAbsolutePath()});
        return resourceFile.getAbsolutePath();
      }
    }

    // if there is value, use it - 4th priority
    {
      String envVariableName = PGEnvironment.PGSYSCONFDIR.getName();
      String pgSysconfDir = System.getenv().get(envVariableName);
      if (pgSysconfDir != null && !pgSysconfDir.trim().isEmpty()) {
        String resourceName = pgSysconfDir + File.separator + pgServceConfFileDefaultName;
        LOGGER.log(Level.FINE, "Value [{0}] selected using environment variable [{1}]", new Object[]{resourceName, envVariableName});
        return resourceName;
      }
    }
    // otherwise null
    LOGGER.log(Level.FINE, "Value for resource [{0}] not found", pgServceConfFileDefaultName);
    return null;
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
  # keys are limited with values described in enum PGEnvironment field name
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
  #   Line: "[service-two]"
  #   Line: "host=host-two"
  #   --> section-one is selected
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
  private @Nullable Properties parseInputStream(InputStream inputStream) throws IOException {
    // build set of allowed keys
    Set<String> allowedServiceKeys = Arrays.stream(PGProperty.values())
        .map(PGProperty::getName)
        .map(PGPropertyUtil::translatePGPropertyToPGService)
        .collect(Collectors.toSet());

    //
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
          continue;
        } else if (indexOfEqualSign > 1) {
          // get key and value
          String key = line.substring(0, indexOfEqualSign);
          String value = line.substring(indexOfEqualSign + 1);
          // check key against set of allowed keys
          if (!allowedServiceKeys.contains(key)) {
            // log list of allowed keys
            String allowedValuesCommaSeparated =
                allowedServiceKeys.stream().sorted().collect(Collectors.joining(","));
            LOGGER.log(Level.SEVERE, "Got invalid key: line number [{0}], value [{1}], allowed "
                    + "values [{2}]",
                new Object[]{lineNumber, originalLine, allowedValuesCommaSeparated});
            // stop processing because of invalid key
            return null;
          }
          // ignore line if value is missing
          if (!value.isEmpty()) {
            // ignore line having duplicate key, otherwise store key-value pair
            result.putIfAbsent(PGPropertyUtil.translatePGServiceToPGProperty(key), value);
          }
        } else {
          // if not equal sign then stop processing because of invalid syntax
          LOGGER.log(Level.WARNING, "Not valid line: line number [{0}], value [{1}]",
              new Object[]{lineNumber, originalLine});
          return null;
        }
      }
    }
    // null means failure - service is not found
    return isFound ? result : null;
  }

}
