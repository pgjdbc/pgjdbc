package org.postgresql;

import java.sql.Array;
import java.sql.SQLException;

public interface PGArraySupport {

  public Array createArrayOf(String typeName, Object elements) throws SQLException;
}
