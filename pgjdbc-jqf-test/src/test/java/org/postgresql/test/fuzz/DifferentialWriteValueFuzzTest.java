/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.fuzz;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.compat.DifferentialProbe;
import org.postgresql.compat.LegacyDriverLoader;
import org.postgresql.compat.ObservableOutcome;
import org.postgresql.compat.OutcomeComparator;
import org.postgresql.test.TestUtil;
import org.postgresql.test.data.EdgeCase;
import org.postgresql.test.data.Float4EdgeCases;
import org.postgresql.test.data.Float8EdgeCases;
import org.postgresql.test.data.Int2EdgeCases;
import org.postgresql.test.data.Int4EdgeCases;
import org.postgresql.test.data.Int8EdgeCases;
import org.postgresql.test.data.NumericEdgeCases;
import org.postgresql.test.data.TextEdgeCases;

import edu.berkeley.cs.jqf.junit5.FuzzTest;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Differential value fuzzer: the value-axis complement of the deterministic {@code
 * BackwardCompatMatrixTest}. jetCheck generates the value, and each trial binds it through the current
 * driver and a released baseline (loaded in an isolated class loader) and reads it back, failing when the
 * observable outcomes diverge. It isolates the send-side encode and its round-trip fidelity for weird
 * values -- numeric scales, non-finite doubles, boundary timestamps, awkward strings -- that a fixed
 * matrix cannot enumerate.
 *
 * <p>Runs in binary transfer, where encode differences surface. Needs a database and the baseline jar
 * path in {@code pgjdbc.compat.legacyJar} (the build supplies it); it aborts when either is missing.
 *
 * <p>Run as bounded regression with {@code gradle -PpgjdbcJqf :pgjdbc-jqf-test:test}; fuzz with
 * {@code -Djqf.fuzz=true -Djqf.fuzz.trials=20000}.
 */
class DifferentialWriteValueFuzzTest {
  private static @Nullable LegacyDriverLoader baselineLoader;
  private static @Nullable Connection current;
  private static @Nullable Connection baseline;

  @BeforeAll
  static void openConnections() throws Exception {
    String jar = System.getProperty("pgjdbc.compat.legacyJar");
    assumeTrue(jar != null && !jar.isEmpty(), "pgjdbc.compat.legacyJar is not set");
    Path jarPath = Paths.get(jar);
    assumeTrue(jarPath.toFile().isFile(), "baseline jar does not exist: " + jarPath);

    String url = TestUtil.getURL();
    try {
      baselineLoader = new LegacyDriverLoader(jarPath);
      current = new org.postgresql.Driver().connect(url, binaryProps());
      baseline = baselineLoader.connect(url, binaryProps());
    } catch (Exception e) {
      assumeTrue(false, "database or baseline driver unavailable: " + e);
    }
  }

  private static Properties binaryProps() {
    Properties p = new Properties();
    String user = TestUtil.getUser();
    if (user != null) {
      p.setProperty("user", user);
    }
    String password = TestUtil.getPassword();
    if (password != null) {
      p.setProperty("password", password);
    }
    p.setProperty("binaryTransfer", "true");
    p.setProperty("prepareThreshold", "-1");
    return p;
  }

  private static void compare(String castType, @Nullable Object value) {
    ObservableOutcome cur = DifferentialProbe.writeValue(castNonNull(current), castType, value);
    ObservableOutcome base = DifferentialProbe.writeValue(castNonNull(baseline), castType, value);
    String diff = OutcomeComparator.compare(cur, base);
    if (diff != null) {
      fail(castType + " value=[" + value + "] -> " + diff);
    }
  }

  @FuzzTest
  void numericRoundTrip(long unscaled, int scale) {
    // Vary the scale across the numeric range; the unscaled part spans the whole long range.
    BigDecimal value = new BigDecimal(BigInteger.valueOf(unscaled), Math.floorMod(scale, 39));
    compare("numeric", value);
  }

  @FuzzTest
  void int8RoundTrip(long value) {
    compare("int8", value);
  }

  @FuzzTest
  void float8RoundTrip(long bits) {
    // Decode from raw bits so the corpus covers NaN, the infinities and subnormals, which a plain
    // double generator rarely hits.
    compare("float8", Double.longBitsToDouble(bits));
  }

  @FuzzTest
  void textRoundTrip(String value) {
    compare("text", value);
  }

  @FuzzTest
  void timestampRoundTrip(long epochMilli) {
    compare("timestamp", new Timestamp(epochMilli));
  }

  /**
   * The seed corpus: bind every catalogue edge value that has a Java form through the same write
   * round-trip and fail on any divergence. It runs deterministically on every build (the known-interesting
   * inputs), while the {@code @FuzzTest} methods above explore around them. Read-only edge cases (temporal,
   * geometric, uuid, ...) carry no bind value and are exercised by the read matrix instead.
   */
  @Test
  void catalogSeedCorpus() {
    List<String> unexpected = new ArrayList<>();
    seed(unexpected, "numeric", NumericEdgeCases.ALL);
    seed(unexpected, "int2", Int2EdgeCases.ALL);
    seed(unexpected, "int4", Int4EdgeCases.ALL);
    seed(unexpected, "int8", Int8EdgeCases.ALL);
    seed(unexpected, "float4", Float4EdgeCases.ALL);
    seed(unexpected, "float8", Float8EdgeCases.ALL);
    seed(unexpected, "text", TextEdgeCases.ALL);
    if (!unexpected.isEmpty()) {
      fail("Catalog seed corpus: current and baseline disagree on " + unexpected.size() + " value(s):\n  "
          + String.join("\n  ", unexpected));
    }
  }

  private static void seed(List<String> unexpected, String castType, List<EdgeCase> cases) {
    for (EdgeCase edge : cases) {
      Object value = edge.value();
      if (value == null || isKnownParityDifference(castType, edge.name())) {
        continue;
      }
      ObservableOutcome cur = DifferentialProbe.writeValue(castNonNull(current), castType, value);
      ObservableOutcome base = DifferentialProbe.writeValue(castNonNull(baseline), castType, value);
      String diff = OutcomeComparator.compare(cur, base);
      if (diff != null) {
        unexpected.add(castType + "|" + edge.name() + " -> " + diff);
      }
    }
  }

  /**
   * Seed cells where the current driver deliberately diverges from the 42.7 baseline, registered as
   * server-parity fixes in {@code KnownDifferences} for the read matrix. numeric {@code tiny}:
   * getString now renders the plain form where the baseline used scientific notation ({@code 1E-20}).
   */
  private static boolean isKnownParityDifference(String castType, String caseName) {
    return "numeric".equals(castType) && "tiny".equals(caseName);
  }

  private static <T> T castNonNull(@Nullable T value) {
    if (value == null) {
      throw new IllegalStateException("connection was not initialized");
    }
    return value;
  }

  @AfterAll
  static void closeConnections() throws Exception {
    closeQuietly(current);
    closeQuietly(baseline);
    if (baselineLoader != null) {
      baselineLoader.close();
    }
  }

  private static void closeQuietly(@Nullable Connection c) {
    if (c == null) {
      return;
    }
    try {
      c.close();
    } catch (Exception ignore) {
      // best-effort close of test connections
    }
  }
}
