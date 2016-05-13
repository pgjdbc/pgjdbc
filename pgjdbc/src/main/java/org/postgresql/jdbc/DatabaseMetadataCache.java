package org.postgresql.jdbc;

import org.postgresql.util.CanEstimateSize;

import java.util.List;

public class DatabaseMetadataCache implements CanEstimateSize{
  private final List<CacheMetadataField> list;

  protected DatabaseMetadataCache(List<CacheMetadataField> list) {
    this.list = list;
  }

  public List<CacheMetadataField> getList() {
    return list;
  }

  @Override
  public long getSize() {
    return list.size();
  }
}
