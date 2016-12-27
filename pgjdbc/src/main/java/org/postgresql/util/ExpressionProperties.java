/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import java.util.Properties;

public class ExpressionProperties extends Properties {

  /**
   * Prefix for constant names
   */
  private static final String PREFIX = "${";

  /**
   * Suffix for constant names
   */
  private static final String SUFFIX = "}";

  /**
   * Creates an empty property list with no default values.
   */
  public ExpressionProperties() {
    super();
  }

  /**
   * Creates an empty property list with the specified defaults.
   *
   * @param defaults java.util.Properties
   */
  public ExpressionProperties(Properties defaults) {
    super(defaults);
  }

  /**
   * This method expands the constants between the PREFIX and SUFFIX and return the normalized value.
   *
   * The method returns <code>null</code> if the property is not found.
   *
   * @param key the property key.
   *
   * @return the value in this property list with
   *         the specified key value.
   */
  public String getProperty(String key) {
    String value = super.getProperty(key);
    if (value == null) {
      return null;
    }
    // Get the index of the first constant, if any
    int beginIndex = 0;
    int startName = value.indexOf(PREFIX, beginIndex);

    while (startName != -1) {
      int endName = value.indexOf(SUFFIX, startName);
      if (endName == -1) {
        // Terminating symbol not found return the value as is
        return value;
      }

      String constName = value.substring(startName + 1, endName);
      String constValue = getProperty(constName);

      if (constValue == null) {
        // Property name not found return the value as is
        return value;
      }

      // Insert the constant value into the original property value
      String newValue = (startName > 0) ? value.substring(0, startName) : "";
      newValue += constValue;

      // Start checking for constants at this index
      beginIndex = newValue.length();

      // Append the remainder of the value
      newValue += value.substring(endName + 1);

      value = newValue;

      // Look for the next constant
      startName = value.indexOf(PREFIX, beginIndex);
    }

    // Return the value as is
    return value;
  }
}
