/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.compat;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.test.TestUtil;
import org.postgresql.test.data.Bit1EdgeCases;
import org.postgresql.test.data.BoolEdgeCases;
import org.postgresql.test.data.BoxEdgeCases;
import org.postgresql.test.data.ByteaEdgeCases;
import org.postgresql.test.data.CidrEdgeCases;
import org.postgresql.test.data.CircleEdgeCases;
import org.postgresql.test.data.DateEdgeCases;
import org.postgresql.test.data.DateRangeEdgeCases;
import org.postgresql.test.data.EdgeCase;
import org.postgresql.test.data.Float4EdgeCases;
import org.postgresql.test.data.Float8EdgeCases;
import org.postgresql.test.data.InetEdgeCases;
import org.postgresql.test.data.Int2EdgeCases;
import org.postgresql.test.data.Int4EdgeCases;
import org.postgresql.test.data.Int4RangeEdgeCases;
import org.postgresql.test.data.Int8EdgeCases;
import org.postgresql.test.data.IntArrayEdgeCases;
import org.postgresql.test.data.IntervalEdgeCases;
import org.postgresql.test.data.JsonEdgeCases;
import org.postgresql.test.data.LineEdgeCases;
import org.postgresql.test.data.LsegEdgeCases;
import org.postgresql.test.data.Macaddr8EdgeCases;
import org.postgresql.test.data.MacaddrEdgeCases;
import org.postgresql.test.data.MoneyEdgeCases;
import org.postgresql.test.data.NumRangeEdgeCases;
import org.postgresql.test.data.NumericEdgeCases;
import org.postgresql.test.data.OidEdgeCases;
import org.postgresql.test.data.PathEdgeCases;
import org.postgresql.test.data.PointEdgeCases;
import org.postgresql.test.data.PolygonEdgeCases;
import org.postgresql.test.data.TextArrayEdgeCases;
import org.postgresql.test.data.TextEdgeCases;
import org.postgresql.test.data.TimeEdgeCases;
import org.postgresql.test.data.TimeTzEdgeCases;
import org.postgresql.test.data.TimestampEdgeCases;
import org.postgresql.test.data.TimestampTzEdgeCases;
import org.postgresql.test.data.TsRangeEdgeCases;
import org.postgresql.test.data.UuidEdgeCases;
import org.postgresql.test.data.VarbitEdgeCases;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Differential backward-compatibility oracle. It drives the public JDBC read and write surface through the
 * current driver and a released baseline loaded in an isolated class loader, then fails on any observable
 * difference that is not recorded in {@link KnownDifferences}.
 *
 * <p>The oracle needs a database (same connection settings as the rest of the suite) and the baseline jar
 * path in {@code pgjdbc.compat.legacyJar}, which the build supplies. It aborts (rather than fails) when
 * either is missing, so it is inert in environments that cannot run it.
 *
 * <p>One method drives the whole matrix on shared connections, so it runs single-threaded.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class BackwardCompatMatrixTest {
  /** Read-half types: {@code {typeName, castExpression}}. The literal is read back through every accessor. */
  private static final String[][] READ_TYPES = {
      {"int2", "'32767'::int2"},
      {"int4", "'2147483647'::int4"},
      {"int8", "'9223372036854775807'::int8"},
      {"numeric", "'12345.6700'::numeric"},
      {"float4", "'3.5'::float4"},
      {"float8", "'3.14159265358979'::float8"},
      {"bool", "'t'::bool"},
      {"text", "'hello'::text"},
      {"varchar", "'hi'::varchar"},
      {"bpchar", "'ab'::char(4)"},
      {"char", "'a'::\"char\""},
      {"bytea", "'\\xdeadbeef'::bytea"},
      {"date", "'2020-01-02'::date"},
      {"time", "'12:34:56'::time"},
      {"timetz", "'12:34:56+03'::timetz"},
      {"timestamp", "'2020-01-02 12:34:56'::timestamp"},
      {"timestamptz", "'2020-01-02 12:34:56+03'::timestamptz"},
      {"uuid", "'00000000-0000-0000-0000-000000000001'::uuid"},
      {"json", "'{\"a\": 1}'::json"},
      {"jsonb", "'{\"a\": 1}'::jsonb"},
      {"oid", "'42'::oid"},
      {"interval", "'1 day 02:03:04'::interval"},
      {"point", "'(1,2)'::point"},
      {"int4arr", "'{1,2,3}'::int4[]"},
      {"textarr", "'{a,b}'::text[]"},
  };

  /** Accessors exercised against numeric/int edge cases: the numeric-relevant getters. */
  private static final Accessor[] NUMERIC_EDGE_ACCESSORS = {
      Accessor.GET_BYTE, Accessor.GET_SHORT, Accessor.GET_INT, Accessor.GET_LONG,
      Accessor.GET_BIG_DECIMAL, Accessor.GET_DOUBLE, Accessor.GET_STRING, Accessor.GET_OBJECT,
  };

  /** Accessors for float4/float8 edge cases: adds getFloat to the numeric set. */
  private static final Accessor[] FLOAT_EDGE_ACCESSORS = {
      Accessor.GET_BYTE, Accessor.GET_SHORT, Accessor.GET_INT, Accessor.GET_LONG,
      Accessor.GET_FLOAT, Accessor.GET_DOUBLE, Accessor.GET_BIG_DECIMAL,
      Accessor.GET_STRING, Accessor.GET_OBJECT,
  };

  /** Accessors for interval edge cases: string/object plus the wire bytes. */
  private static final Accessor[] INTERVAL_EDGE_ACCESSORS = {
      Accessor.GET_STRING, Accessor.GET_OBJECT, Accessor.GET_BYTES,
  };

  /** Accessors for timestamp edge cases: the temporal getters and the JSR-310 targets. */
  private static final Accessor[] TIMESTAMP_EDGE_ACCESSORS = {
      Accessor.GET_STRING, Accessor.GET_OBJECT, Accessor.GET_DATE, Accessor.GET_TIME,
      Accessor.GET_TIMESTAMP, Accessor.GET_OBJECT_LOCAL_DATE_TIME, Accessor.GET_OBJECT_INSTANT,
      Accessor.GET_OBJECT_LOCAL_DATE,
  };

  /** Accessors for date edge cases. */
  private static final Accessor[] DATE_EDGE_ACCESSORS = {
      Accessor.GET_STRING, Accessor.GET_OBJECT, Accessor.GET_DATE, Accessor.GET_TIMESTAMP,
      Accessor.GET_OBJECT_LOCAL_DATE, Accessor.GET_OBJECT_LOCAL_DATE_TIME,
  };

  /** Accessors for time edge cases. */
  private static final Accessor[] TIME_EDGE_ACCESSORS = {
      Accessor.GET_STRING, Accessor.GET_OBJECT, Accessor.GET_TIME, Accessor.GET_TIMESTAMP,
      Accessor.GET_OBJECT_LOCAL_TIME,
  };

  /** Accessors for timetz edge cases. */
  private static final Accessor[] TIMETZ_EDGE_ACCESSORS = {
      Accessor.GET_STRING, Accessor.GET_OBJECT, Accessor.GET_TIME, Accessor.GET_OBJECT_OFFSET_TIME,
  };

  /** Accessors for timestamptz edge cases: temporal getters plus the offset/instant JSR-310 targets. */
  private static final Accessor[] TIMESTAMPTZ_EDGE_ACCESSORS = {
      Accessor.GET_STRING, Accessor.GET_OBJECT, Accessor.GET_DATE, Accessor.GET_TIME,
      Accessor.GET_TIMESTAMP, Accessor.GET_OBJECT_OFFSET_DATE_TIME, Accessor.GET_OBJECT_INSTANT,
      Accessor.GET_OBJECT_LOCAL_DATE_TIME,
  };

  /** Accessors for uuid edge cases: string/object plus the typed target and the wire bytes. */
  private static final Accessor[] UUID_EDGE_ACCESSORS = {
      Accessor.GET_STRING, Accessor.GET_OBJECT, Accessor.GET_OBJECT_UUID, Accessor.GET_BYTES,
  };

  /** Accessors for bit/varbit edge cases: string/object, the boolean view (bit(1)), and the wire bytes. */
  private static final Accessor[] BIT_EDGE_ACCESSORS = {
      Accessor.GET_STRING, Accessor.GET_OBJECT, Accessor.GET_BOOLEAN, Accessor.GET_BYTES,
  };

  /** Accessors for bool edge cases: string/object, the boolean view, and the wire bytes. */
  private static final Accessor[] BOOL_EDGE_ACCESSORS = {
      Accessor.GET_STRING, Accessor.GET_OBJECT, Accessor.GET_BOOLEAN, Accessor.GET_BYTES,
  };

  /** Accessors for money edge cases: the numeric views plus string/object. */
  private static final Accessor[] MONEY_EDGE_ACCESSORS = {
      Accessor.GET_STRING, Accessor.GET_OBJECT, Accessor.GET_DOUBLE, Accessor.GET_BIG_DECIMAL,
      Accessor.GET_LONG,
  };

  /** Accessors for the network types (inet/cidr/macaddr/macaddr8): string/object plus the wire bytes. */
  private static final Accessor[] NETWORK_EDGE_ACCESSORS = {
      Accessor.GET_STRING, Accessor.GET_OBJECT, Accessor.GET_BYTES,
  };

  /** Accessors for bytea edge cases: the byte views plus string/object. */
  private static final Accessor[] BYTEA_EDGE_ACCESSORS = {
      Accessor.GET_BYTES, Accessor.GET_STRING, Accessor.GET_OBJECT, Accessor.GET_OBJECT_BYTES,
  };

  /** Accessors for json/jsonb edge cases: string/object plus the wire bytes. */
  private static final Accessor[] JSON_EDGE_ACCESSORS = {
      Accessor.GET_STRING, Accessor.GET_OBJECT, Accessor.GET_BYTES,
  };

  /** Accessors for array edge cases: getArray plus string/object and the wire bytes. */
  private static final Accessor[] ARRAY_EDGE_ACCESSORS = {
      Accessor.GET_STRING, Accessor.GET_OBJECT, Accessor.GET_ARRAY, Accessor.GET_BYTES,
  };

  /** Accessors for text/point edge cases: string/object plus the wire bytes. */
  private static final Accessor[] STRING_OBJECT_BYTES_ACCESSORS = {
      Accessor.GET_STRING, Accessor.GET_OBJECT, Accessor.GET_BYTES,
  };

  /** Accessors for the array-wrapping axis: the two ways to read an array back. */
  private static final Accessor[] ARRAY_WRAP_ACCESSORS = {
      Accessor.GET_ARRAY, Accessor.GET_OBJECT,
  };

  /** CallableStatement read axis: {@code {pgTypeName, argExpression, java.sql.Types out-param code}}. */
  private static final Object[][] CALLABLE_TYPES = {
      {"int2", "'32767'::int2", Types.SMALLINT},
      {"int4", "'2147483647'::int4", Types.INTEGER},
      {"int8", "'9223372036854775807'::int8", Types.BIGINT},
      {"numeric", "'12345.6700'::numeric", Types.NUMERIC},
      {"float4", "'3.5'::float4", Types.REAL},
      {"float8", "'3.14159265358979'::float8", Types.DOUBLE},
      {"bool", "'t'::bool", Types.BOOLEAN},
      {"text", "'hello'::text", Types.VARCHAR},
      {"bytea", "'\\xdeadbeef'::bytea", Types.BINARY},
      {"date", "'2020-01-02'::date", Types.DATE},
      {"time", "'12:34:56'::time", Types.TIME},
      {"timetz", "'12:34:56+03'::timetz", Types.TIME_WITH_TIMEZONE},
      {"timestamp", "'2020-01-02 12:34:56'::timestamp", Types.TIMESTAMP},
      {"timestamptz", "'2020-01-02 12:34:56+03'::timestamptz", Types.TIMESTAMP_WITH_TIMEZONE},
  };

  /** setObject(PGobject) sub-axis: {@code {pgTypeName, value}}. The type name doubles as the cast target. */
  private static final String[][] PGOBJECT_TYPES = {
      {"json", "{\"a\": 1}"},
      {"jsonb", "{\"a\": 1}"},
      {"uuid", "00000000-0000-0000-0000-000000000001"},
      {"inet", "192.168.0.1"},
      {"point", "(1,2)"},
      {"interval", "1 day 02:03:04"},
  };

  private LegacyDriverLoader baseline;
  private Connection currentText;
  private Connection currentBinary;
  private Connection baselineText;
  private Connection baselineBinary;

  @BeforeAll
  void openConnections() throws Exception {
    String jar = System.getProperty("pgjdbc.compat.legacyJar");
    assumeTrue(jar != null && !jar.isEmpty(),
        "pgjdbc.compat.legacyJar is not set; the build passes it via a CommandLineArgumentProvider");
    Path jarPath = Paths.get(jar);
    assumeTrue(jarPath.toFile().isFile(), "baseline jar does not exist: " + jarPath);

    String url = TestUtil.getURL();
    try {
      this.baseline = new LegacyDriverLoader(jarPath);
      org.postgresql.Driver current = new org.postgresql.Driver();
      this.currentText = current.connect(url, props(false));
      this.currentBinary = current.connect(url, props(true));
      this.baselineText = baseline.connect(url, props(false));
      this.baselineBinary = baseline.connect(url, props(true));
      for (Connection c : new Connection[]{currentText, currentBinary, baselineText, baselineBinary}) {
        DifferentialProbe.createEchoFunction(c);
      }
    } catch (Exception e) {
      // The property/jar checks above already made the oracle inert when it is not configured to run.
      // Reaching here means it IS configured but the database or the baseline driver could not be
      // brought up, so fail loudly rather than silently skip the compatibility gate.
      throw new IllegalStateException(
          "compat oracle is configured but the database or baseline driver is unavailable: " + e, e);
    }
  }

  private static Properties props(boolean binary) {
    Properties p = new Properties();
    String user = TestUtil.getUser();
    if (user != null) {
      p.setProperty("user", user);
    }
    String password = TestUtil.getPassword();
    if (password != null) {
      p.setProperty("password", password);
    }
    if (binary) {
      p.setProperty("binaryTransfer", "true");
      // Force a server-prepared statement so binary transfer is actually used for supported types.
      p.setProperty("prepareThreshold", "-1");
    } else {
      p.setProperty("binaryTransfer", "false");
    }
    return p;
  }

  /** Extracts the {@link BigDecimal} from a {@code '<value>'::numeric} read expression. */
  private static BigDecimal numericLiteralValue(String selectExpr) {
    int start = selectExpr.indexOf('\'') + 1;
    int end = selectExpr.indexOf('\'', start);
    return new BigDecimal(selectExpr.substring(start, end));
  }

  @Test
  void observableBehaviourMatchesBaseline() {
    List<String> unexpected = new ArrayList<>();

    for (String format : new String[]{"text", "binary"}) {
      boolean binary = "binary".equals(format);
      Connection cur = binary ? currentBinary : currentText;
      Connection base = binary ? baselineBinary : baselineText;

      // Read half: every accessor on every fixed server value.
      for (String[] type : READ_TYPES) {
        String typeName = type[0];
        String selectSql = "SELECT " + type[1];
        for (Accessor accessor : Accessor.values()) {
          ObservableOutcome curOutcome = DifferentialProbe.read(cur, selectSql, accessor);
          ObservableOutcome baseOutcome = DifferentialProbe.read(base, selectSql, accessor);
          String diff = OutcomeComparator.compare(curOutcome, baseOutcome);
          if (diff != null) {
            String label = KnownDifferences.readLabel(format, typeName, accessor);
            // For the fixed numeric/float reads, pass the value so the server-parity rounding rule
            // applies: numeric->intN rounds half-away-from-zero, float8->intN rounds ties-to-even,
            // both where the baseline truncated toward zero.
            String reason;
            if ("numeric".equals(typeName)) {
              reason = KnownDifferences.acceptNumericEdge(label, numericLiteralValue(type[1]),
                  curOutcome, baseOutcome);
            } else if ("float4".equals(typeName) || "float8".equals(typeName)) {
              reason = KnownDifferences.acceptFloatEdge(label, numericLiteralValue(type[1]),
                  curOutcome, baseOutcome);
            } else {
              reason = KnownDifferences.accept(label, curOutcome, baseOutcome);
            }
            if (reason == null) {
              unexpected.add(label + " -> " + diff);
            }
          }
        }
      }

      // Write half: every setter, bound and read back canonically.
      for (Binder binder : Binder.values()) {
        ObservableOutcome curOutcome = DifferentialProbe.write(cur, binder);
        ObservableOutcome baseOutcome = DifferentialProbe.write(base, binder);
        String diff = OutcomeComparator.compare(curOutcome, baseOutcome);
        if (diff != null) {
          String label = KnownDifferences.writeLabel(format, binder);
          if (KnownDifferences.accept(label, curOutcome, baseOutcome) == null) {
            unexpected.add(label + " -> " + diff);
          }
        }
      }

      // Edge-case axes: rounding/overflow near boundaries, specials, precision and resolution limits.
      runEdgeAxis(unexpected, "numeric-edge", format, cur, base, "numeric",
          NumericEdgeCases.ALL, NUMERIC_EDGE_ACCESSORS);
      runEdgeAxis(unexpected, "int4-edge", format, cur, base, "int4",
          Int4EdgeCases.ALL, NUMERIC_EDGE_ACCESSORS);
      runEdgeAxis(unexpected, "int2-edge", format, cur, base, "int2",
          Int2EdgeCases.ALL, NUMERIC_EDGE_ACCESSORS);
      runEdgeAxis(unexpected, "float4-edge", format, cur, base, "float4",
          Float4EdgeCases.ALL, FLOAT_EDGE_ACCESSORS);
      runEdgeAxis(unexpected, "float8-edge", format, cur, base, "float8",
          Float8EdgeCases.ALL, FLOAT_EDGE_ACCESSORS);
      runEdgeAxis(unexpected, "interval-edge", format, cur, base, "interval",
          IntervalEdgeCases.ALL, INTERVAL_EDGE_ACCESSORS);
      runEdgeAxis(unexpected, "timestamp-edge", format, cur, base, "timestamp",
          TimestampEdgeCases.ALL, TIMESTAMP_EDGE_ACCESSORS);
      runEdgeAxis(unexpected, "date-edge", format, cur, base, "date",
          DateEdgeCases.ALL, DATE_EDGE_ACCESSORS);
      runEdgeAxis(unexpected, "time-edge", format, cur, base, "time",
          TimeEdgeCases.ALL, TIME_EDGE_ACCESSORS);
      runEdgeAxis(unexpected, "timetz-edge", format, cur, base, "timetz",
          TimeTzEdgeCases.ALL, TIMETZ_EDGE_ACCESSORS);
      runEdgeAxis(unexpected, "timestamptz-edge", format, cur, base, "timestamptz",
          TimestampTzEdgeCases.ALL, TIMESTAMPTZ_EDGE_ACCESSORS);
      runEdgeAxis(unexpected, "uuid-edge", format, cur, base, "uuid",
          UuidEdgeCases.ALL, UUID_EDGE_ACCESSORS);
      runEdgeAxis(unexpected, "varbit-edge", format, cur, base, "varbit",
          VarbitEdgeCases.ALL, BIT_EDGE_ACCESSORS);
      runEdgeAxis(unexpected, "bit1-edge", format, cur, base, "bit(1)",
          Bit1EdgeCases.ALL, BIT_EDGE_ACCESSORS);
      runEdgeAxis(unexpected, "money-edge", format, cur, base, "money",
          MoneyEdgeCases.ALL, MONEY_EDGE_ACCESSORS);
      runEdgeAxis(unexpected, "inet-edge", format, cur, base, "inet",
          InetEdgeCases.ALL, NETWORK_EDGE_ACCESSORS);
      runEdgeAxis(unexpected, "cidr-edge", format, cur, base, "cidr",
          CidrEdgeCases.ALL, NETWORK_EDGE_ACCESSORS);
      runEdgeAxis(unexpected, "macaddr-edge", format, cur, base, "macaddr",
          MacaddrEdgeCases.ALL, NETWORK_EDGE_ACCESSORS);
      runEdgeAxis(unexpected, "macaddr8-edge", format, cur, base, "macaddr8",
          Macaddr8EdgeCases.ALL, NETWORK_EDGE_ACCESSORS);
      runEdgeAxis(unexpected, "bytea-edge", format, cur, base, "bytea",
          ByteaEdgeCases.ALL, BYTEA_EDGE_ACCESSORS);
      runEdgeAxis(unexpected, "json-edge", format, cur, base, "json",
          JsonEdgeCases.ALL, JSON_EDGE_ACCESSORS);
      runEdgeAxis(unexpected, "jsonb-edge", format, cur, base, "jsonb",
          JsonEdgeCases.ALL, JSON_EDGE_ACCESSORS);
      runEdgeAxis(unexpected, "int4arr-edge", format, cur, base, "int4[]",
          IntArrayEdgeCases.ALL, ARRAY_EDGE_ACCESSORS);
      runEdgeAxis(unexpected, "textarr-edge", format, cur, base, "text[]",
          TextArrayEdgeCases.ALL, ARRAY_EDGE_ACCESSORS);
      runEdgeAxis(unexpected, "int8-edge", format, cur, base, "int8",
          Int8EdgeCases.ALL, NUMERIC_EDGE_ACCESSORS);
      runEdgeAxis(unexpected, "oid-edge", format, cur, base, "oid",
          OidEdgeCases.ALL, NUMERIC_EDGE_ACCESSORS);
      runEdgeAxis(unexpected, "bool-edge", format, cur, base, "bool",
          BoolEdgeCases.ALL, BOOL_EDGE_ACCESSORS);
      runEdgeAxis(unexpected, "text-edge", format, cur, base, "text",
          TextEdgeCases.ALL, STRING_OBJECT_BYTES_ACCESSORS);
      runEdgeAxis(unexpected, "point-edge", format, cur, base, "point",
          PointEdgeCases.ALL, STRING_OBJECT_BYTES_ACCESSORS);
      runEdgeAxis(unexpected, "line-edge", format, cur, base, "line",
          LineEdgeCases.ALL, STRING_OBJECT_BYTES_ACCESSORS);
      runEdgeAxis(unexpected, "lseg-edge", format, cur, base, "lseg",
          LsegEdgeCases.ALL, STRING_OBJECT_BYTES_ACCESSORS);
      runEdgeAxis(unexpected, "box-edge", format, cur, base, "box",
          BoxEdgeCases.ALL, STRING_OBJECT_BYTES_ACCESSORS);
      runEdgeAxis(unexpected, "path-edge", format, cur, base, "path",
          PathEdgeCases.ALL, STRING_OBJECT_BYTES_ACCESSORS);
      runEdgeAxis(unexpected, "polygon-edge", format, cur, base, "polygon",
          PolygonEdgeCases.ALL, STRING_OBJECT_BYTES_ACCESSORS);
      runEdgeAxis(unexpected, "circle-edge", format, cur, base, "circle",
          CircleEdgeCases.ALL, STRING_OBJECT_BYTES_ACCESSORS);
      runEdgeAxis(unexpected, "int4range-edge", format, cur, base, "int4range",
          Int4RangeEdgeCases.ALL, STRING_OBJECT_BYTES_ACCESSORS);
      runEdgeAxis(unexpected, "numrange-edge", format, cur, base, "numrange",
          NumRangeEdgeCases.ALL, STRING_OBJECT_BYTES_ACCESSORS);
      runEdgeAxis(unexpected, "tsrange-edge", format, cur, base, "tsrange",
          TsRangeEdgeCases.ALL, STRING_OBJECT_BYTES_ACCESSORS);
      runEdgeAxis(unexpected, "daterange-edge", format, cur, base, "daterange",
          DateRangeEdgeCases.ALL, STRING_OBJECT_BYTES_ACCESSORS);

      // Array-wrapping axis: wrap each scalar edge value in a 1-D and a 2-D array and check the two
      // ways of reading it back (getArray, getObject) still agree between the drivers.
      runArrayAxis(unexpected, "numeric-arr-edge", format, cur, base, "numeric", NumericEdgeCases.ALL);
      runArrayAxis(unexpected, "int4-arr-edge", format, cur, base, "int4", Int4EdgeCases.ALL);
      runArrayAxis(unexpected, "int8-arr-edge", format, cur, base, "int8", Int8EdgeCases.ALL);
      runArrayAxis(unexpected, "float8-arr-edge", format, cur, base, "float8", Float8EdgeCases.ALL);
      runArrayAxis(unexpected, "uuid-arr-edge", format, cur, base, "uuid", UuidEdgeCases.ALL);
      runArrayAxis(unexpected, "timestamp-arr-edge", format, cur, base, "timestamp",
          TimestampEdgeCases.ALL);
      runArrayAxis(unexpected, "text-arr-edge", format, cur, base, "text", TextEdgeCases.ALL);

      // CallableStatement half: read the value back through a function's out parameter.
      for (Object[] type : CALLABLE_TYPES) {
        String typeName = (String) type[0];
        String argExpr = (String) type[1];
        int registerType = (Integer) type[2];
        for (CsAccessor accessor : CsAccessor.values()) {
          ObservableOutcome curOutcome = DifferentialProbe.readCallable(cur, argExpr, registerType, accessor);
          ObservableOutcome baseOutcome =
              DifferentialProbe.readCallable(base, argExpr, registerType, accessor);
          String diff = OutcomeComparator.compare(curOutcome, baseOutcome);
          if (diff != null) {
            String label = KnownDifferences.callableLabel(format, typeName, accessor);
            if (KnownDifferences.accept(label, curOutcome, baseOutcome) == null) {
              unexpected.add(label + " -> " + diff);
            }
          }
        }
      }

      // setObject(PGobject) half: bind a driver-specific PGobject built in each driver's own loader.
      ClassLoader currentLoader = PgObjects.class.getClassLoader();
      for (String[] type : PGOBJECT_TYPES) {
        String typeName = type[0];
        String label = KnownDifferences.pgObjectLabel(format, typeName);
        ObservableOutcome curOutcome;
        ObservableOutcome baseOutcome;
        try {
          Object currentValue = PgObjects.of(currentLoader, typeName, type[1]);
          Object baselineValue = PgObjects.of(baseline.classLoader(), typeName, type[1]);
          curOutcome = DifferentialProbe.writeValue(cur, typeName, currentValue);
          baseOutcome = DifferentialProbe.writeValue(base, typeName, baselineValue);
        } catch (ReflectiveOperationException e) {
          unexpected.add(label + " -> PGobject construction failed: " + e);
          continue;
        }
        String diff = OutcomeComparator.compare(curOutcome, baseOutcome);
        if (diff != null && KnownDifferences.accept(label, curOutcome, baseOutcome) == null) {
          unexpected.add(label + " -> " + diff);
        }
      }
    }

    List<String> unusedKnownDifferences = KnownDifferences.unusedEntries();

    if (!unexpected.isEmpty() || !unusedKnownDifferences.isEmpty()) {
      StringBuilder sb = new StringBuilder();
      if (!unexpected.isEmpty()) {
        sb.append("Baseline ").append(baseline.version())
            .append(" and the current driver disagree on ").append(unexpected.size())
            .append(" cell(s) not listed in KnownDifferences:\n");
        for (String u : unexpected) {
          sb.append("  ").append(u).append('\n');
        }
        sb.append("Review each: if the change is intended (for example server-parity over a legacy "
            + "quirk), add it to KnownDifferences with a justification; otherwise it is a compatibility "
            + "regression.\n");
      }
      if (!unusedKnownDifferences.isEmpty()) {
        sb.append(unusedKnownDifferences.size())
            .append(" KnownDifferences entr(y/ies) never matched: the divergence they guarded has "
                + "converged, so remove them (a dead entry can mask a future regression):\n");
        for (String u : unusedKnownDifferences) {
          sb.append("  ").append(u).append('\n');
        }
      }
      fail(sb.toString());
    }
  }

  /**
   * Reads every edge case of one type through a set of accessors on both drivers and records the
   * unexpected differences. {@code axis} names the cell group in the label. Numeric-edge cases carry a
   * {@link BigDecimal} value, which feeds the rounding-parity acceptance; other types carry none and fall
   * back to the generic registry.
   */
  private static void runEdgeAxis(List<String> unexpected, String axis, String format,
      Connection cur, Connection base, String pgType, List<EdgeCase> cases, Accessor[] accessors) {
    for (EdgeCase edge : cases) {
      String selectSql = "SELECT '" + edge.literal() + "'::" + pgType;
      BigDecimal roundingValue = edge.value() instanceof BigDecimal ? (BigDecimal) edge.value() : null;
      for (Accessor accessor : accessors) {
        ObservableOutcome curOutcome = DifferentialProbe.read(cur, selectSql, accessor);
        ObservableOutcome baseOutcome = DifferentialProbe.read(base, selectSql, accessor);
        String diff = OutcomeComparator.compare(curOutcome, baseOutcome);
        if (diff != null) {
          String label = KnownDifferences.edgeLabel(axis, format, edge.name(), accessor);
          String reason = axis.startsWith("float")
              ? KnownDifferences.acceptFloatEdge(label, roundingValue, curOutcome, baseOutcome)
              : KnownDifferences.acceptNumericEdge(label, roundingValue, curOutcome, baseOutcome);
          if (reason == null) {
            unexpected.add(label + " -> " + diff);
          }
        }
      }
    }
  }

  /**
   * Wraps each scalar edge value of one type in a single-element 1-D array ({@code ARRAY[v]}) and 2-D
   * array ({@code ARRAY[ARRAY[v]]}) and checks that reading it back agrees between the drivers. The {@code
   * ARRAY[...]} constructor takes typed element expressions, so no array-literal quoting is needed and the
   * same scalar catalogues drive the array path.
   */
  private static void runArrayAxis(List<String> unexpected, String axis, String format,
      Connection cur, Connection base, String elemType, List<EdgeCase> cases) {
    for (EdgeCase edge : cases) {
      String element = "'" + edge.literal() + "'::" + elemType;
      runArrayCell(unexpected, axis, format, cur, base, "ARRAY[" + element + "]", "dim1-" + edge.name());
      runArrayCell(unexpected, axis, format, cur, base,
          "ARRAY[ARRAY[" + element + "]]", "dim2-" + edge.name());
    }
  }

  private static void runArrayCell(List<String> unexpected, String axis, String format,
      Connection cur, Connection base, String arrayExpr, String caseName) {
    String selectSql = "SELECT " + arrayExpr;
    for (Accessor accessor : ARRAY_WRAP_ACCESSORS) {
      ObservableOutcome curOutcome = DifferentialProbe.read(cur, selectSql, accessor);
      ObservableOutcome baseOutcome = DifferentialProbe.read(base, selectSql, accessor);
      String diff = OutcomeComparator.compare(curOutcome, baseOutcome);
      if (diff != null) {
        String label = KnownDifferences.edgeLabel(axis, format, caseName, accessor);
        if (KnownDifferences.accept(label, curOutcome, baseOutcome) == null) {
          unexpected.add(label + " -> " + diff);
        }
      }
    }
  }

  @AfterAll
  void closeConnections() throws Exception {
    closeQuietly(currentText);
    closeQuietly(currentBinary);
    closeQuietly(baselineText);
    closeQuietly(baselineBinary);
    if (baseline != null) {
      baseline.close();
    }
  }

  private static void closeQuietly(Connection c) {
    if (c == null) {
      return;
    }
    try {
      c.close();
    } catch (Exception ignore) {
      // closing test connections is best-effort
    }
  }
}
