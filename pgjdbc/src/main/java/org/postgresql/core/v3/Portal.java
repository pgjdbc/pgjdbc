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
  Portal(@Nullable SimpleQuery query, @Nullable ServerHandle handle, String portalName) {
    this.query = query;
    this.portalName = portalName;
    this.encodedName = portalName.getBytes(StandardCharsets.UTF_8);
    this.cleanup = new Cleanup(portalName, handle);
    if (handle != null) {
      handle.pin();
    }
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

  /**
   * Returns the server statement this portal was bound from. The backend closes all dependent
   * portals when a statement is closed, so the statement must not be closed while this portal is
   * open; the pin taken in the constructor enforces that.
   *
   * @return the server statement this portal was bound from, or null for the unnamed portal
   */
  @Nullable ServerHandle getHandle() {
    return cleanup.handle;
  }

  void setCleanupRef(PhantomReference<?> cleanupRef) {
    this.cleanupRef = cleanupRef;
  }

  Cleanup getCleanup() {
    return cleanup;
  }

  /**
   * Releases the pin this portal holds on its statement. Idempotent; shared with the
   * phantom-reference cleanup path through {@link #getCleanup()}, so an explicit release on an
   * error path and a later {@code processDeadPortals} run release the pin exactly once.
   */
  void releaseHandlePin() {
    cleanup.releaseHandlePin();
  }

  /**
   * The portal state the executor needs after the {@link Portal} object itself became
   * unreachable: the name to send {@code Close} for, and the statement pin to release. Must not
   * reference the portal, or the phantom reference would never be enqueued.
   */
  static final class Cleanup {
    final String portalName;
    final @Nullable ServerHandle handle;
    private boolean pinReleased;

    Cleanup(String portalName, @Nullable ServerHandle handle) {
      this.portalName = portalName;
      this.handle = handle;
    }

    void releaseHandlePin() {
      ServerHandle handle = this.handle;
      if (!pinReleased && handle != null) {
        handle.unpin();
        pinReleased = true;
      }
    }
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
  private final Cleanup cleanup;
  private @Nullable PhantomReference<?> cleanupRef;
}
