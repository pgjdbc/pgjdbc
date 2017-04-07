/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import java.io.Serializable;
import java.util.Comparator;

/**
 * Created by davec on 4/7/17.
 */
public class PGPropertyComparator<K> implements Comparator, Serializable {

  public PGPropertyComparator() {
    super();
  }

  @Override
  public int compare(Object o1, Object o2) {
    if (o1 instanceof String && o2 instanceof String) {
      return compare((String) o1, (String) o2);
    }
    return 0;
  }

  public int compare(String str1, String str2) {
    return str1.compareToIgnoreCase(str2);
  }

}
