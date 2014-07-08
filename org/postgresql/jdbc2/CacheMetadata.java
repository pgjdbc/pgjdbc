package org.postgresql.jdbc2;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.postgresql.core.Field;

public class CacheMetadata {
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
    
    for (int i = 0 ; i < fields.length ; i++) {
      CacheMetadataField c = new CacheMetadataField(fields[i]);
      liste.add(c);
    }
    
    _cache.put(idFields, liste);
  }
  
  protected String getIdFields(Field[] fields) {
    StringBuffer sb = new StringBuffer();
    
    for (int i = 0 ; i < fields.length ; i++) {
      sb.append(getIdField(fields[i])).append('/');
    }
    
    return sb.toString();
  }
  
  private String getIdField(Field f) {
    return f.getTableOid() + "." + f.getPositionInTable();
  }
}

class CacheMetadataField {
  private String colName;
  private String tabName;
  private String schemaName;
  private int nullable;
  private boolean auto;
  
  protected CacheMetadataField(Field f) {
    colName = f.getColumnName();
    tabName = f.getTableName();
    schemaName = f.getSchemaName();
    nullable = f.getNullable();
    auto = f.getAutoIncrement();
  }
  
  protected void get(Field f) {
    f.setColumnName(colName);
    f.setTableName(tabName);
    f.setSchemaName(schemaName);
    f.setNullable(nullable);
    f.setAutoIncrement(auto);
  }
}
