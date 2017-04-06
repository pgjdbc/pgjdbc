/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import java.util.Properties;
import java.util.TreeMap;

/**
 * Created by davec on 4/5/17.
 */
public class PGProperties extends TreeMap<Object, Object> {

  public PGProperties() {
    super();
  }

  public PGProperties(Properties properties) {
    if (properties != null) {
      putAll(properties);
    }
  }

  public PGProperties(PGProperties properties) {
    if (properties != null) {
      putAll(properties);
    }
  }

  public String get(Object key, Object defaultValue) {
    if (containsKey(key)) {
      return (String) super.get(key);
    }
    return (String) defaultValue;
  }

  public String get(Object key) {
    if (containsKey(key)) {
      return (String) super.get(key);
    }
    return null;
  }

  public void set(Object key, Object value) {
    if (value != null) {
      put(key, value);
    }
  }

  public Properties getProperties() {
    Properties properties = new Properties();
    forEach((key, value) -> properties.put(key, value));
    return properties;
  }


}
