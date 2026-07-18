/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.postgresql.core.NativeQuery;
import org.postgresql.core.Oid;
import org.postgresql.core.Parser;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

/**
 * White-box tests for the per-signature statement table of {@link SimpleQuery}:
 * {@code findPreparedFor} lookup with MRU promotion, {@code takeHandleForPrepare} slot selection,
 * pin-aware eviction, and deferred close. The pinned branches are not reachable through the JDBC
 * API yet (re-executing a statement closes its ResultSet, and the portal drain runs before handle
 * resolution), but the connection-wide cap planned on top of this table will reach them.
 */
class HandleVariantTableTest {

  private static final short EPOCH = 7;

  private static final int[] INT4 = {Oid.INT4};
  private static final int[] VARCHAR = {Oid.VARCHAR};
  private static final int[] INT8 = {Oid.INT8};
  private static final int[] FLOAT8 = {Oid.FLOAT8};

  private int statementCounter;

  private SimpleQuery newQuery() throws SQLException {
    List<NativeQuery> queries = Parser.parseJdbcSql("SELECT ?", true, true, true, false, false);
    return new SimpleQuery(queries.get(0), null, false);
  }

  /** Mirrors what sendParse does once a statement gets (re-)prepared. */
  private void prepare(ServerHandle handle, int[] types) {
    if (handle.getStatementName() != null) {
      handle.unprepare();
    }
    handle.setStatementName("S_" + (statementCounter++), EPOCH);
    handle.setPrepareTypes(types);
  }

  private ServerHandle resolveForPrepare(SimpleQuery query, int[] types, int maxVariants) {
    if (query.getHandle().isPreparedFor(types, EPOCH, false)) {
      return query.getHandle();
    }
    ServerHandle variant = query.findPreparedFor(types, EPOCH);
    if (variant != null) {
      return variant;
    }
    return query.takeHandleForPrepare(maxVariants);
  }

  @Test
  void singleVariantReusesTheHandleInPlace() throws SQLException {
    SimpleQuery query = newQuery();
    ServerHandle first = resolveForPrepare(query, INT4, 1);
    prepare(first, INT4);

    ServerHandle second = resolveForPrepare(query, VARCHAR, 1);
    assertSame(first, second, "maxVariants=1 re-prepares the single statement in place");
  }

  @Test
  void secondSignatureGetsItsOwnSlotAndBothStayReachable() throws SQLException {
    SimpleQuery query = newQuery();
    ServerHandle forInt = resolveForPrepare(query, INT4, 2);
    prepare(forInt, INT4);

    ServerHandle forVarchar = resolveForPrepare(query, VARCHAR, 2);
    assertNotSame(forInt, forVarchar, "second signature must not evict the only statement");
    prepare(forVarchar, VARCHAR);
    assertNotNull(forInt.getStatementName(), "first statement stays prepared");

    // Alternating lookups hit without re-prepares, promoting the hit to the head.
    assertSame(forInt, resolveForPrepare(query, INT4, 2));
    assertSame(forInt, query.getHandle(), "hit is promoted to most recently used");
    assertSame(forVarchar, resolveForPrepare(query, VARCHAR, 2));
    assertSame(forVarchar, query.getHandle());
  }

  @Test
  void thirdSignatureEvictsLeastRecentlyUsed() throws SQLException {
    SimpleQuery query = newQuery();
    ServerHandle forInt = resolveForPrepare(query, INT4, 2);
    prepare(forInt, INT4);
    ServerHandle forVarchar = resolveForPrepare(query, VARCHAR, 2);
    prepare(forVarchar, VARCHAR);

    // int4 is the least recently used; the int8 execution reuses its slot.
    ServerHandle forInt8 = resolveForPrepare(query, INT8, 2);
    assertSame(forInt, forInt8, "LRU statement slot is reused for the new signature");
    prepare(forInt8, INT8);

    assertSame(forVarchar, resolveForPrepare(query, VARCHAR, 2),
        "the more recently used statement survives the eviction");
  }

  @Test
  void pinnedLeastRecentlyUsedIsSkipped() throws SQLException {
    SimpleQuery query = newQuery();
    ServerHandle forInt = resolveForPrepare(query, INT4, 3);
    prepare(forInt, INT4);
    ServerHandle forVarchar = resolveForPrepare(query, VARCHAR, 3);
    prepare(forVarchar, VARCHAR);
    ServerHandle forInt8 = resolveForPrepare(query, INT8, 3);
    prepare(forInt8, INT8);

    forInt.pin(); // an open portal depends on the LRU statement

    ServerHandle forFloat8 = resolveForPrepare(query, FLOAT8, 3);
    assertSame(forVarchar, forFloat8, "eviction skips the pinned statement and takes the next LRU");
    assertNotNull(forInt.getStatementName(), "pinned statement is not closed");

    forInt.unpin();
    assertNotNull(forInt.getStatementName(), "a plain unpin does not close the statement");
  }

  @Test
  void allPinnedDefersCloseOfLeastRecentlyUsed() throws SQLException {
    SimpleQuery query = newQuery();
    ServerHandle forInt = resolveForPrepare(query, INT4, 2);
    prepare(forInt, INT4);
    ServerHandle forVarchar = resolveForPrepare(query, VARCHAR, 2);
    prepare(forVarchar, VARCHAR);

    forInt.pin();
    forVarchar.pin();

    ServerHandle forInt8 = resolveForPrepare(query, INT8, 2);
    assertNotSame(forInt, forInt8, "no pinned statement may be reused");
    assertNotSame(forVarchar, forInt8, "no pinned statement may be reused");
    prepare(forInt8, INT8);

    assertNotNull(forInt.getStatementName(),
        "the deferred statement survives while its portal is open");
    forInt.unpin();
    assertNull(forInt.getStatementName(),
        "the deferred statement closes at the unpin that releases the last portal");

    forVarchar.unpin();
    assertNotNull(forVarchar.getStatementName(),
        "a statement that stayed in the table is not closed by its unpin");
    assertSame(forVarchar, resolveForPrepare(query, VARCHAR, 2),
        "the statement that stayed in the table remains reachable");
  }

  @Test
  void staleEpochEntriesAreDroppedByTheScan() throws SQLException {
    SimpleQuery query = newQuery();
    ServerHandle forInt = resolveForPrepare(query, INT4, 2);
    prepare(forInt, INT4);
    ServerHandle forVarchar = resolveForPrepare(query, VARCHAR, 2);
    prepare(forVarchar, VARCHAR);

    short bumped = (short) (EPOCH + 1);
    assertNull(query.findPreparedFor(INT4, bumped),
        "no statement from an older epoch may be returned");
    assertNull(forInt.getStatementName(),
        "the stale statement is closed as the scan walks past it");
  }

  @Test
  void unprepareClosesEveryVariant() throws SQLException {
    SimpleQuery query = newQuery();
    ServerHandle forInt = resolveForPrepare(query, INT4, 2);
    prepare(forInt, INT4);
    ServerHandle forVarchar = resolveForPrepare(query, VARCHAR, 2);
    prepare(forVarchar, VARCHAR);

    query.unprepare();
    assertNull(forInt.getStatementName());
    assertNull(forVarchar.getStatementName());
    assertNull(query.findPreparedFor(INT4, EPOCH));
    assertNull(query.findPreparedFor(VARCHAR, EPOCH));
  }
}
