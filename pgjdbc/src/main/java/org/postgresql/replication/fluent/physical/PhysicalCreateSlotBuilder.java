/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.replication.fluent.physical;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.core.BaseConnection;
import org.postgresql.replication.LogSequenceNumber;
import org.postgresql.replication.ReplicationSlotInfo;
import org.postgresql.replication.ReplicationType;
import org.postgresql.replication.fluent.AbstractCreateSlotBuilder;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class PhysicalCreateSlotBuilder
    extends AbstractCreateSlotBuilder<ChainedPhysicalCreateSlotBuilder>
    implements ChainedPhysicalCreateSlotBuilder {

  public PhysicalCreateSlotBuilder(BaseConnection connection) {
    super(connection);
  }

  @Override
  protected ChainedPhysicalCreateSlotBuilder self() {
    return this;
  }

  @Override
  public ReplicationSlotInfo make() throws SQLException {
    if (slotName == null || slotName.isEmpty()) {
      throw new IllegalArgumentException("Replication slotName can't be null");
    }

    Statement statement = connection.createStatement();
    ResultSet result = null;
    ReplicationSlotInfo slotInfo = null;
    try {
      String sql = String.format(
          "CREATE_REPLICATION_SLOT %s %s PHYSICAL",
          slotName,
          temporaryOption ? "TEMPORARY" : ""
      );
      statement.execute(sql);
      result = statement.getResultSet();
      if (result != null && result.next()) {
        slotInfo = new ReplicationSlotInfo(
            castNonNull(result.getString("slot_name")),
            ReplicationType.PHYSICAL,
            LogSequenceNumber.valueOf(castNonNull(result.getString("consistent_point"))),
            result.getString("snapshot_name"),
            result.getString("output_plugin"));
      } else {
        throw new PSQLException(
            GT.tr("{0} returned no results"),
            PSQLState.OBJECT_NOT_IN_STATE);
      }
    } finally {
      if (result != null) {
        result.close();
      }
      statement.close();
    }
    return slotInfo;
  }
}
