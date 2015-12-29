package org.postgresql.jdbc;

import org.postgresql.core.Field;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class CacheMetadata {
  // FIXME: unsynchronized hashmap might lead to thread stuck
  private static Map<String, List<CacheMetadataField>> _cache =
      new HashMap<String, List<CacheMetadataField>>();

  protected boolean isCached(String idFields) {
    return _cache.containsKey(idFields);
  }

  protected void getCache(String idFields, Field[] fields) {
    List<CacheMetadataField> liste = _cache.get(idFields);
    if (liste != null) {
      int no = 0;
      for (CacheMetadataField c : liste) {
        c.get(fields[no++]);
      }
    }
  }

  protected void setCache(String idFields, Field[] fields) {
    List<CacheMetadataField> liste = new LinkedList<CacheMetadataField>();

    for (Field field : fields) {
      CacheMetadataField c = new CacheMetadataField(field);
      liste.add(c);
    }

    _cache.put(idFields, liste);
  }

  protected String getIdFields(Field[] fields) {
    StringBuilder sb = new StringBuilder();

    for (Field field : fields) {
      sb.append(getIdField(field)).append('/');
    }

    return sb.toString();
  }

  private String getIdField(Field f) {
    return f.getTableOid() + "." + f.getPositionInTable();
  }
}
