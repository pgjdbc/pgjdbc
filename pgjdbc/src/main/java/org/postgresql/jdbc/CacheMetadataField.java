package org.postgresql.jdbc;

import org.postgresql.core.Field;
import org.postgresql.util.CanEstimateSize;

public class CacheMetadataField implements CanEstimateSize{
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

  public long getSize() {
    return colName.length()
            + tabName.length()
            + schemaName.length()
            + 4
            + 1;
  }
}
