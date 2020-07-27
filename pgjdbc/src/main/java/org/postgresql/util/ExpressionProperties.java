/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.nullness.qual.PolyNull;
import org.checkerframework.checker.regex.qual.Regex;

import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ExpressionProperties extends Properties {

  private static final @Regex(1) Pattern EXPRESSION = Pattern.compile("\\$\\{([^}]+)\\}");

  private final Properties[] defaults;

  /**
   * Creates an empty property list with the specified defaults.
   *
   * @param defaults java.util.Properties
   */
  public ExpressionProperties(Properties ...defaults) {
    this.defaults = defaults;
  }

  /**
   * <p>Returns property value with all {@code ${propKey}} like references replaced with the value of
   * the relevant property with recursive resolution.</p>
   *
   * <p>The method returns <code>null</code> if the property is not found.</p>
   *
   * @param key the property key.
   *
   * @return the value in this property list with
   *         the specified key value.
   */
  @Override
  public @Nullable String getProperty(String key) {
    String value = getRawPropertyValue(key);
    return replaceProperties(value);
  }

  @Override
  public @PolyNull String getProperty(String key, @PolyNull String defaultValue) {
    String value = getRawPropertyValue(key);
    if (value == null) {
      value = defaultValue;
    }
    return replaceProperties(value);
  }

  /**
   * Returns raw value of a property without any replacements.
   * @param key property name
   * @return raw property value
   */
  public @Nullable String getRawPropertyValue(String key) {
    String value = super.getProperty(key);
    if (value != null) {
      return value;
    }
    for (Properties properties : defaults) {
      value = properties.getProperty(key);
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  private @PolyNull String replaceProperties(@PolyNull String value) {
    if (value == null) {
      return null;
    }
    Matcher matcher = EXPRESSION.matcher(value);
    StringBuffer sb = null;
    while (matcher.find()) {
      if (sb == null) {
        sb = new StringBuffer();
      }
      String propValue = getProperty(castNonNull(matcher.group(1)));
      if (propValue == null) {
        // Use original content like ${propKey} if property is not found
        propValue = matcher.group();
      }
      matcher.appendReplacement(sb, Matcher.quoteReplacement(propValue));
    }
    if (sb == null) {
      return value;
    }
    matcher.appendTail(sb);
    return sb.toString();
  }
}
