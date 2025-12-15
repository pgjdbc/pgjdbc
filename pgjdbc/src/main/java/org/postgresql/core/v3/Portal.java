/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */
// Copyright (c) 2004, Open Cloud Limited.

package org.postgresql.core.v3;

import org.postgresql.core.ResultCursor;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.ref.PhantomReference;
import java.nio.charset.StandardCharsets;

/**
 * V3 ResultCursor implementation in terms of backend Portals. This holds the state of a single
 * Portal. We use a PhantomReference managed by our caller to handle resource cleanup.
 *
 * @author Oliver Jowett (oliver@opencloud.com)
 */
class Portal implements ResultCursor {
  Portal(@Nullable SimpleQuery query, String portalName) {
    this.query = query;
    this.portalName = portalName;
    this.encodedName = portalName.getBytes(StandardCharsets.UTF_8);
  }

  @Override
  public void close() {
    PhantomReference<?> cleanupRef = this.cleanupRef;
    if (cleanupRef != null) {
      cleanupRef.clear();
      cleanupRef.enqueue();
      this.cleanupRef = null;
    }
  }

  String getPortalName() {
    return portalName;
  }

  byte[] getEncodedPortalName() {
    return encodedName;
  }

  @Nullable SimpleQuery getQuery() {
    return query;
  }

  void setCleanupRef(PhantomReference<?> cleanupRef) {
    this.cleanupRef = cleanupRef;
  }

  int getCursorOptions() {
    return cursorOptions;
  }

  void setCursorOptions(int options) {
    this.cursorOptions = options;
  }

  @Override
  public String toString() {
    return portalName;
  }

  // Holding on to a reference to the generating query has
  // the nice side-effect that while this Portal is referenced,
  // so is the SimpleQuery, so the underlying statement won't
  // be closed while the portal is open (the backend closes
  // all open portals when the statement is closed)

  private final @Nullable SimpleQuery query;
  private final String portalName;
  private final byte[] encodedName;
  private @Nullable PhantomReference<?> cleanupRef;
  private int cursorOptions = 0;
}
