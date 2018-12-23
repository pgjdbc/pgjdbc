/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.udt;

import org.postgresql.core.BaseConnection;

import java.sql.SQLException;
import java.util.UUID;


// TODO: Review for conversion compatibility with PgResultSet
//       Or best - shared implementation
public class UUIDValueAccess extends BaseValueAccess {

  private final int oid;
  private final UUID value;

  // TODO: oid passed here just because UIDArrayAssistant registers itself for both
  //       Oid.UUID and Oid.UUID_ARRAY
  public UUIDValueAccess(BaseConnection connection, int oid, UUID value, UdtMap udtMap) {
    super(connection, udtMap);
    this.oid = oid;
    this.value = value;
  }

  @Override
  protected int getOid() {
    return oid;
  }

  @Override
  public String getString() throws SQLException {
    return value.toString();
  }

  @Override
  public Object getObject() throws SQLException {
    // TODO: typemap?
    return value;
  }
}
