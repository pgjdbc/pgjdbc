package org.postgresql.core;

import java.sql.SQLException;

/**
 * Interface for creating holdable portals via the extended query protocol.
 * Holdable portals survive transaction commits.
 */
public interface HoldablePortal {
  
  /**
   * Binds a prepared statement to a named portal with cursor options.
   * 
   * @param portalName the portal name (required for holdable cursors)
   * @param statementName the prepared statement name
   * @param parameters the parameter values
   * @param cursorOptions cursor options bitmask (see {@link CursorOptions})
   * @throws SQLException if binding fails
   */
  void bindWithOptions(String portalName, String statementName, 
                       ParameterList parameters, int cursorOptions) throws SQLException;
}
