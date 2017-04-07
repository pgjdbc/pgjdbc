/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Created by davec on 4/5/17.
 */
public class PGProperties extends TreeMap<Object, Object> {

  public PGProperties() {

    super(new PGPropertyComparator());
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

  public PGProperties load( InputStream inputStream ) throws IOException {
    Properties properties = new Properties();
    properties.load(inputStream);
    return new PGProperties(properties);
  }

  public Properties getProperties() {
    Properties properties = new Properties();
    Iterator<Object> keys = this.keySet().iterator();
    while ( keys.hasNext() ) {
      String key = (String)keys.next();
      properties.put( key, get(key) );
    }
    return properties;
  }

}
