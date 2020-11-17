/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.replication.fluent;

import org.postgresql.core.BaseConnection;
import org.postgresql.core.ServerVersion;
import org.postgresql.util.GT;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.SQLFeatureNotSupportedException;

public abstract class AbstractCreateSlotBuilder<T extends ChainedCommonCreateSlotBuilder<T>>
    implements ChainedCommonCreateSlotBuilder<T> {

  protected @Nullable String slotName;
  protected boolean temporaryOption = false;
  protected BaseConnection connection;

  protected AbstractCreateSlotBuilder(BaseConnection connection) {
    this.connection = connection;
  }

  protected abstract T self();

  @Override
  public T withSlotName(String slotName) {
    this.slotName = slotName;
    return self();
  }

  @Override
  public T withTemporaryOption() throws SQLFeatureNotSupportedException {

    if (!connection.haveMinimumServerVersion(ServerVersion.v10)) {
      throw new SQLFeatureNotSupportedException(
          GT.tr("Server does not support temporary replication slots")
      );
    }

    this.temporaryOption = true;
    return self();
  }
}
