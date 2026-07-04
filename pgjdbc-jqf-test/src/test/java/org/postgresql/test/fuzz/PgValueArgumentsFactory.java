/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.fuzz;

import org.postgresql.core.Oid;
import org.postgresql.fuzzkit.CoercionCase;
import org.postgresql.fuzzkit.CoercionRoundTripCase;
import org.postgresql.fuzzkit.CoercionRoundTripSupport;
import org.postgresql.fuzzkit.CoercionWriteCase;
import org.postgresql.fuzzkit.FuzzArray;
import org.postgresql.fuzzkit.FuzzNode;
import org.postgresql.fuzzkit.FuzzRecord;
import org.postgresql.fuzzkit.FuzzSqlData;
import org.postgresql.fuzzkit.ReadOracle;
import org.postgresql.fuzzkit.SqlInputReader;
import org.postgresql.fuzzkit.SqlOutputWriterBinding;
import org.postgresql.fuzzkit.coercion.ArrayDescriptor;
import org.postgresql.fuzzkit.coercion.CompositeDescriptor;
import org.postgresql.fuzzkit.coercion.LeafRepr;
import org.postgresql.fuzzkit.coercion.PgTypeDescriptors;
import org.postgresql.fuzzkit.coercion.ScalarDescriptor;

import edu.berkeley.cs.jqf.fuzz.jetcheck.JetCheckArgumentsGenerator;
import edu.berkeley.cs.jqf.fuzz.spi.ArgumentsGenerator;
import edu.berkeley.cs.jqf.fuzz.spi.ArgumentsGeneratorFactory;
import org.jetbrains.jetCheck.GenerationEnvironment;
import org.jetbrains.jetCheck.Generator;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.net.MalformedURLException;
import java.net.URL;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A jetCheck-backed argument provider for the container and non-primitive scalar fuzz targets. The
 * stock {@code jqf-generator-jetcheck} provider maps only primitives and {@link String}; this one
 * adds the shapes the codec fuzzer needs -- variable-length {@code Integer[]} / {@code String[]}
 * (with occasional SQL NULLs), {@code byte[]} (bytea), {@link BigDecimal} (numeric), and a
 * {@link FuzzRecord} whose field count, field types, and values all vary.
 *
 * <p>Because it produces types the stock provider rejects, a target selects it explicitly with
 * {@code @FuzzTest(arguments = PgValueArgumentsFactory.class)}. Every value is drawn through
 * {@link JetCheckArgumentsGenerator} from the engine's guided byte stream, so Zest's mutations map
 * onto local structural changes and {@code jqf:repro} replays them exactly.
 */
public final class PgValueArgumentsFactory implements ArgumentsGeneratorFactory {

  /** Biases collection lengths; the exact length is still drawn from the byte stream. */
  private static final int SIZE_HINT = 12;

  private static final Generator<String> NULLABLE_STRING =
      Generator.frequency(1, Generator.<String>constant(null), 9,
          Generator.stringsOf(Generator.asciiPrintableChars()));

  // The array fuzz target: a FuzzArray over a randomly chosen registered array descriptor (int4[] or
  // text[]). The generator draws ndim from the descriptor's dims and leafRepr from its leafReprs, then a
  // rectangular nested array of the leaf class. A boxed leaf may carry SQL NULLs; a primitive leaf is
  // non-null only (the recommended C3 step -- the NULL-into-primitive refusal is a separate oracle
  // track). The descriptor OID travels inside the FuzzArray, so one target covers every array type.
  private static final ArrayDescriptor[] ARRAY_DESCRIPTORS =
      PgTypeDescriptors.arrays().toArray(new ArrayDescriptor[0]);

  private static final Generator<FuzzArray> FUZZ_ARRAY = Generator.from(env -> {
    ArrayDescriptor descriptor =
        ARRAY_DESCRIPTORS[env.generate(Generator.integers(0, ARRAY_DESCRIPTORS.length - 1))];
    return drawArray(env, descriptor);
  });

  // Package-private: ValueGenerators indexes the coercion suite's shared scalar generators by class.
  static final Generator<byte[]> BYTEA =
      Generator.listsOf(Generator.integers(Byte.MIN_VALUE, Byte.MAX_VALUE).map(Integer::byteValue))
          .map(PgValueArgumentsFactory::toByteArray);

  // unscaled * 10^-scale spans small and large magnitudes with up to 12 fractional digits.
  static final Generator<BigDecimal> NUMERIC =
      Generator.zipWith(Generator.integers(), Generator.integers(0, 12),
          (unscaled, scale) -> BigDecimal.valueOf(unscaled.longValue(), scale));

  // The record-field membership is derived, not hand-listed: a scalar descriptor takes part iff its
  // default getObject class is equals-stable and config-independent, so the decoded attribute compares
  // equal by Objects.equals (FuzzRecord's comparison) under any connection config. That admits
  // {int2, int4, int8, float4, float8, bool, text} -- the seven whose default class is one of
  // EQUALS_STABLE_OBJECT_CLASSES -- and rejects numeric (BigDecimal compares by scale, not equals),
  // bytea (byte[] equals is identity), and the temporal types (their default class flips with
  // prefersJavaTime). float4 was absent from the earlier hand-written list; deriving the set adds it.
  // The field VALUE is of the descriptor's defaultObjectClass -- for int2 that is Integer (pgjdbc's
  // documented smallint->Integer backward-compat), not the naturalClass Short, so the attributes match.
  private static final Set<Class<?>> EQUALS_STABLE_OBJECT_CLASSES = equalsStableObjectClasses();

  // The config that turns on every prefersJavaTime view, used to detect a config-dependent default
  // class: a type whose default getObject class differs under it is temporal and does not qualify.
  private static final Map<String, String> ALL_JAVA_TIME = allJavaTimeConfig();

  /** A record field: an OID drawn together with a value of its equals-stable default getObject class. */
  private static final Generator<Object[]> FIELD = recordFieldGenerator();

  private static Set<Class<?>> equalsStableObjectClasses() {
    // The value-based-equals boxed scalars: excludes BigDecimal (scale-sensitive equals) and byte[]
    // (array-identity equals), the two whose default class is not stable under Objects.equals.
    Set<Class<?>> classes = new LinkedHashSet<>();
    classes.add(Integer.class);
    classes.add(Long.class);
    classes.add(Float.class);
    classes.add(Double.class);
    classes.add(Boolean.class);
    classes.add(String.class);
    return Collections.unmodifiableSet(classes);
  }

  private static Map<String, String> allJavaTimeConfig() {
    Map<String, String> config = new LinkedHashMap<>();
    config.put("prefersJavaTimeForDate", "true");
    config.put("prefersJavaTimeForTime", "true");
    config.put("prefersJavaTimeForTimetz", "true");
    config.put("prefersJavaTimeForTimestamp", "true");
    config.put("prefersJavaTimeForTimestamptz", "true");
    return Collections.unmodifiableMap(config);
  }

  /**
   * Whether a scalar descriptor's default getObject class is equals-stable and config-independent, so a
   * record attribute of this type reads back equal by {@link java.util.Objects#equals} under any config.
   */
  private static boolean qualifiesAsRecordField(ScalarDescriptor scalar) {
    Class<?> defaultClass = scalar.defaultObjectClass();
    return defaultClass != null
        && EQUALS_STABLE_OBJECT_CLASSES.contains(defaultClass)
        && defaultClass == scalar.defaultObjectClass(ALL_JAVA_TIME);
  }

  /**
   * Builds the record-field generator from the scalar descriptors: every qualifying type contributes a
   * branch that draws an OID-range value of its default getObject class, tagged with the OID. The value
   * generator is looked up per OID from {@link #recordFieldValueGenerator}; a qualifying type without a
   * generator fails class init rather than drop silently from the axis.
   */
  private static Generator<Object[]> recordFieldGenerator() {
    List<Generator<Object[]>> branches = new ArrayList<>();
    Set<Integer> members = new LinkedHashSet<>();
    for (ScalarDescriptor scalar : PgTypeDescriptors.scalars()) {
      if (!qualifiesAsRecordField(scalar)) {
        continue;
      }
      members.add(scalar.oid());
      int oid = scalar.oid();
      branches.add(recordFieldValueGenerator(oid).map(value -> new Object[]{oid, value}));
    }
    // The criterion must derive exactly the seven equals-stable, config-independent scalars, float4
    // among them. If it ever drifts, fail class init here rather than silently reshape the record axis.
    Set<Integer> expected = new LinkedHashSet<>(Arrays.asList(
        Oid.INT2, Oid.INT4, Oid.INT8, Oid.FLOAT4, Oid.FLOAT8, Oid.BOOL, Oid.TEXT));
    if (!members.equals(expected)) {
      throw new ExceptionInInitializerError(
          "derived record-field OIDs " + members + " diverge from the expected seven " + expected);
    }
    return Generator.anyOf(branches);
  }

  /**
   * The value generator for a record field of the given OID: it draws a value of the type's default
   * getObject class within the OID's wire range, matching the distribution each field used before the
   * membership became derived (int2 draws an int2-range Integer, int8 an int-range Long, and so on).
   *
   * @param oid the field's type OID (a derived record-field member)
   * @return the value generator for that field
   */
  private static Generator<?> recordFieldValueGenerator(int oid) {
    switch (oid) {
      case Oid.INT2:
        // int2 decodes to an Integer in int2 range, not a Short, so the attribute matches.
        return Generator.integers(Short.MIN_VALUE, Short.MAX_VALUE);
      case Oid.INT4:
        return Generator.integers();
      case Oid.INT8:
        return Generator.integers().map(Integer::longValue);
      case Oid.FLOAT4:
        return Generator.doubles().map(Double::floatValue);
      case Oid.FLOAT8:
        return Generator.doubles();
      case Oid.BOOL:
        return Generator.booleans();
      case Oid.TEXT:
        return Generator.stringsOf(Generator.asciiPrintableChars());
      default:
        throw new ExceptionInInitializerError(
            "no record-field value generator for derived member OID " + oid);
    }
  }

  private static final Generator<FuzzRecord> RECORD =
      Generator.listsOf(FIELD).map(PgValueArgumentsFactory::toRecord);

  private static final Generator<FuzzNode.Field> SCALAR_FIELD =
      FIELD.map(f -> FuzzNode.Field.scalar((Integer) f[0], f[1]));

  // A named composite: PostgreSQL forbids a `record`-typed column, so a named node's record
  // children are themselves named. The 5:1 bias toward scalars keeps the tree shallow.
  private static final Generator<FuzzNode> NAMED_NODE = Generator.recursive(self -> {
    Generator<FuzzNode.Field> namedChild = self.map(FuzzNode.Field::record);
    Generator<FuzzNode.Field> field = Generator.frequency(5, SCALAR_FIELD, 1, namedChild);
    return Generator.listsOf(field).map(fields -> toNode(fields, false));
  });

  // An anonymous RECORD: its fields may be scalars, named composites, or further anonymous records
  // -- the mixed shapes PostgreSQL allows in a row() value.
  private static final Generator<FuzzNode> ANON_NODE = Generator.recursive(self -> {
    Generator<FuzzNode.Field> namedChild = NAMED_NODE.map(FuzzNode.Field::record);
    Generator<FuzzNode.Field> anonChild = self.map(FuzzNode.Field::record);
    Generator<FuzzNode.Field> field =
        Generator.frequency(6, SCALAR_FIELD, 1, namedChild, 1, anonChild);
    return Generator.listsOf(field).map(fields -> toNode(fields, true));
  });

  private static final Generator<FuzzNode> STRUCT = Generator.anyOf(NAMED_NODE, ANON_NODE);

  // Rows of the point composite, for the array-of-record target. Each attribute is drawn from the
  // field descriptor's leaf generator (int4 -> Integer, text -> String), so the row shape follows the
  // registered CompositeDescriptor rather than a hand-listed field order.
  private static final Generator<Object[]> POINT_ROW = compositeRowGenerator(PgTypeDescriptors.POINT_OID);

  private static final Generator<Object[][]> POINT_ROWS =
      Generator.listsOf(Generator.frequency(1, Generator.<Object[]>constant(null), 9, POINT_ROW))
          .map(list -> list.toArray(new Object[0][]));

  // Full-width longs from two int draws.
  static final Generator<Long> LONGS = Generator.integers().flatMap(high ->
      Generator.integers().map(low -> ((long) high << 32) | (low & 0xFFFFFFFFL)));

  private static final int MIN_DAY = (int) LocalDate.of(1, 1, 1).toEpochDay();
  private static final int MAX_DAY = (int) LocalDate.of(9999, 12, 31).toEpochDay();

  static final Generator<Date> DATES = Generator.integers(MIN_DAY, MAX_DAY)
      .map(day -> Date.valueOf(LocalDate.ofEpochDay(day)));

  static final Generator<Time> TIMES = Generator.integers(0, 86_399)
      .map(second -> Time.valueOf(LocalTime.ofSecondOfDay(second)));

  // PostgreSQL timestamps carry microseconds, so keep the fractional second at that resolution;
  // full nanoseconds would truncate on the wire and break the round-trip.
  static final Generator<Timestamp> TIMESTAMPS = Generator.integers(MIN_DAY, MAX_DAY)
      .flatMap(day -> Generator.integers(0, 86_399).flatMap(second ->
          Generator.integers(0, 999_999).map(micro -> Timestamp.valueOf(
              LocalDateTime.of(LocalDate.ofEpochDay(day),
                  LocalTime.ofSecondOfDay(second).withNano(micro * 1000))))));

  private static final Generator<ZoneOffset> OFFSETS = Generator.integers(-1080, 1080)
      .map(minutes -> ZoneOffset.ofTotalSeconds(minutes * 60));

  static final Generator<OffsetTime> OFFSET_TIMES = Generator.integers(0, 86_399).flatMap(
      second -> Generator.integers(0, 999_999).flatMap(micro -> OFFSETS.map(
          offset -> OffsetTime.of(LocalTime.ofSecondOfDay(second).withNano(micro * 1000), offset))));

  static final Generator<OffsetDateTime> OFFSET_DATE_TIMES = Generator.integers(MIN_DAY,
      MAX_DAY).flatMap(day -> Generator.integers(0, 86_399).flatMap(second ->
          Generator.integers(0, 999_999).flatMap(micro -> OFFSETS.map(offset -> OffsetDateTime.of(
              LocalDateTime.of(LocalDate.ofEpochDay(day),
                  LocalTime.ofSecondOfDay(second).withNano(micro * 1000)), offset)))));

  private static final Generator<BigDecimal> NULLABLE_NUMERIC = nullable(NUMERIC);
  private static final Generator<byte[]> NULLABLE_BYTES = nullable(BYTEA);
  private static final Generator<Date> NULLABLE_DATE = nullable(DATES);
  private static final Generator<Time> NULLABLE_TIME = nullable(TIMES);
  private static final Generator<Timestamp> NULLABLE_TIMESTAMP = nullable(TIMESTAMPS);

  // One SQLData value per field type, built imperatively from the guided stream. The object fields
  // are nullable so the codec's NULL path (SQLInput.wasNull) is exercised.
  private static final Generator<FuzzSqlData> SQL_DATA = Generator.from(env -> {
    FuzzSqlData value = new FuzzSqlData();
    value.intValue = env.generate(Generator.integers());
    value.longValue = env.generate(LONGS);
    value.shortValue =
        env.generate(Generator.integers(Short.MIN_VALUE, Short.MAX_VALUE)).shortValue();
    value.boolValue = env.generate(Generator.booleans());
    value.floatValue = env.generate(Generator.doubles()).floatValue();
    value.doubleValue = env.generate(Generator.doubles());
    value.text = env.generate(NULLABLE_STRING);
    value.numeric = env.generate(NULLABLE_NUMERIC);
    value.bytes = env.generate(NULLABLE_BYTES);
    value.date = env.generate(NULLABLE_DATE);
    value.time = env.generate(NULLABLE_TIME);
    value.timestamp = env.generate(NULLABLE_TIMESTAMP);
    return value;
  });

  private static <T> Generator<T> nullable(Generator<T> generator) {
    return Generator.frequency(1, Generator.<T>constant(null), 9, generator);
  }

  // The coercion matrix axis: the write-populated scalars only, so it stays on exactly the ten coercion
  // types. The codec-only scalars (int2/float4/float8/bytea) carry descriptors but no WriteCoercions row,
  // so they take no part in the coercion write / round-trip matrix.
  private static final ScalarDescriptor[] DESCRIPTORS =
      PgTypeDescriptors.coercionScalars().toArray(new ScalarDescriptor[0]);
  private static final SqlInputReader[] SQL_INPUT_READERS = SqlInputReader.values();
  private static final SqlOutputWriterBinding[] SQL_OUTPUT_WRITERS = SqlOutputWriterBinding.values();

  /** A fixed URL for {@code writeURL}; only its string form reaches the codec. */
  static final URL SAMPLE_URL = sampleUrl();

  // A field value of a random built-in type on the canonical wire, read back through a random SQLInput
  // reader under random prefersJavaTime flags -- the read matrix. The wire is always canonical: the
  // driver write paths present field bytes identical to the canonical codec on the diagonal (pinned by
  // TypedWriteMatchesCanonicalWireTest), so off-diagonal write->read is the round-trip fuzzer's job.
  private static final Generator<CoercionCase> COERCION = Generator.from(env -> {
    ScalarDescriptor descriptor = DESCRIPTORS[env.generate(Generator.integers(0, DESCRIPTORS.length - 1))];
    Object value = ValueGenerators.draw(env, descriptor.naturalClass());
    SqlInputReader reader =
        SQL_INPUT_READERS[env.generate(Generator.integers(0, SQL_INPUT_READERS.length - 1))];
    int classIndex = env.generate(Generator.integers(0, ReadOracle.TARGET_CLASSES.length - 1));
    Class<?> targetClass =
        reader == SqlInputReader.READ_OBJECT_AS ? ReadOracle.TARGET_CLASSES[classIndex] : null;
    boolean[] prefs = new boolean[5];
    for (int i = 0; i < prefs.length; i++) {
      prefs[i] = env.generate(Generator.booleans());
    }
    return new CoercionCase(descriptor, value, reader, targetClass, prefs);
  });

  // A value written through a random SQLOutput writer into a random built-in attribute type -- the
  // whole SQLData write matrix, off-diagonal included (for example writeInt into a text attribute).
  private static final Generator<CoercionWriteCase> WRITE_COERCION = Generator.from(env -> {
    ScalarDescriptor attr = DESCRIPTORS[env.generate(Generator.integers(0, DESCRIPTORS.length - 1))];
    SqlOutputWriterBinding writer =
        SQL_OUTPUT_WRITERS[env.generate(Generator.integers(0, SQL_OUTPUT_WRITERS.length - 1))];
    return new CoercionWriteCase(attr, writer, ValueGenerators.writeValue(env, writer.method()));
  });

  private static final CoercionRoundTripSupport.IdentityPair[] IDENTITY_PAIRS =
      CoercionRoundTripSupport.IDENTITY_PAIRS.toArray(new CoercionRoundTripSupport.IdentityPair[0]);

  // A value written into a composite attribute, then read back. Half the cases are identity pairs (the
  // type's own writer + reader / readObject) so the round-trip checks value fidelity; the other half are
  // off-diagonal (any writer into any attribute, any reader) so it checks the write and read outcomes and
  // that neither leg leaks -- for example writeString into a time attribute, read back via readTime.
  private static final Generator<CoercionRoundTripCase> ROUND_TRIP = Generator.from(env -> {
    if (env.generate(Generator.booleans())) {
      CoercionRoundTripSupport.IdentityPair pair =
          IDENTITY_PAIRS[env.generate(Generator.integers(0, IDENTITY_PAIRS.length - 1))];
      // Draw the value of the pair's natural class: the object-axis pairs (timetz/timestamptz) write
      // through writeObject, which does not fix the class, so it comes from naturalClass, not the writer.
      Object value = ValueGenerators.draw(env, pair.naturalClass);
      int classes = ReadOracle.TARGET_CLASSES.length;
      int mode = env.generate(Generator.integers(0, classes));
      if (mode == classes) {
        // The type's own typed reader, or -- for an object-axis pair -- readObject of its natural class.
        return pair.reader != null
            ? new CoercionRoundTripCase(pair.attr, pair.writer, value, pair.reader, null)
            : new CoercionRoundTripCase(pair.attr, pair.writer, value, SqlInputReader.READ_OBJECT_AS,
                pair.naturalClass);
      }
      return new CoercionRoundTripCase(pair.attr, pair.writer, value, SqlInputReader.READ_OBJECT_AS,
          ReadOracle.TARGET_CLASSES[mode]);
    }
    ScalarDescriptor attr = DESCRIPTORS[env.generate(Generator.integers(0, DESCRIPTORS.length - 1))];
    SqlOutputWriterBinding writer =
        SQL_OUTPUT_WRITERS[env.generate(Generator.integers(0, SQL_OUTPUT_WRITERS.length - 1))];
    SqlInputReader reader =
        SQL_INPUT_READERS[env.generate(Generator.integers(0, SQL_INPUT_READERS.length - 1))];
    int classIndex = env.generate(Generator.integers(0, ReadOracle.TARGET_CLASSES.length - 1));
    Class<?> target =
        reader == SqlInputReader.READ_OBJECT_AS ? ReadOracle.TARGET_CLASSES[classIndex] : null;
    return new CoercionRoundTripCase(attr, writer, ValueGenerators.writeValue(env, writer.method()),
        reader, target);
  });

  private static URL sampleUrl() {
    try {
      return new URL("http://h/");
    } catch (MalformedURLException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @Override
  public ArgumentsGenerator create(Class<?> testClass, Method testMethod) {
    Class<?>[] parameterTypes = testMethod.getParameterTypes();
    Generator<?>[] generators = new Generator<?>[parameterTypes.length];
    for (int i = 0; i < parameterTypes.length; i++) {
      generators[i] = generatorFor(parameterTypes[i]);
    }
    return JetCheckArgumentsGenerator.builder(generators).sizeHint(SIZE_HINT).build();
  }

  private static Generator<?> generatorFor(Class<?> type) {
    if (type == FuzzArray.class) {
      return FUZZ_ARRAY;
    }
    if (type == byte[].class) {
      return BYTEA;
    }
    if (type == BigDecimal.class) {
      return NUMERIC;
    }
    if (type == FuzzRecord.class) {
      return RECORD;
    }
    if (type == FuzzNode.class) {
      return STRUCT;
    }
    if (type == Object[][].class) {
      return POINT_ROWS;
    }
    if (type == FuzzSqlData.class) {
      return SQL_DATA;
    }
    if (type == CoercionCase.class) {
      return COERCION;
    }
    if (type == CoercionWriteCase.class) {
      return WRITE_COERCION;
    }
    if (type == CoercionRoundTripCase.class) {
      return ROUND_TRIP;
    }
    throw new IllegalArgumentException(
        "PgValueArgumentsFactory generates FuzzArray, byte[], BigDecimal, FuzzRecord, FuzzNode, "
            + "Object[][], FuzzSqlData, CoercionCase, CoercionWriteCase and CoercionRoundTripCase, "
            + "not " + type.getName());
  }

  /**
   * Draws a {@link FuzzArray} for a descriptor: an {@code ndim} from its dims, a {@code leafRepr} from
   * its leaf representations, and a rectangular nested array of the leaf class. A boxed leaf draws one
   * in ten elements as SQL NULL to exercise the container codec's NULL path; a primitive leaf draws
   * non-null values only (the recommended C3 step). The nested array is allocated with the full length
   * vector, so it is rectangular and carries the exact runtime component type the codec decodes to.
   */
  private static FuzzArray drawArray(GenerationEnvironment env, ArrayDescriptor descriptor) {
    List<Integer> dims = descriptor.dims();
    int ndim = dims.get(env.generate(Generator.integers(0, dims.size() - 1)));
    List<LeafRepr> reprs = new ArrayList<>(descriptor.leafReprs());
    LeafRepr leafRepr = reprs.get(env.generate(Generator.integers(0, reprs.size() - 1)));

    int[] lengths = new int[ndim];
    for (int d = 0; d < ndim; d++) {
      // 0..3 per dimension keeps multi-dimensional arrays small; a zero length yields an empty array.
      lengths[d] = env.generate(Generator.integers(0, 3));
    }

    Class<?> leafClass = descriptor.leafClass(leafRepr);
    Object array = Array.newInstance(leafClass, lengths);
    // The boxed leaf generator: the element's shared scalar generator (from ValueGenerators), made
    // nullable for the boxed representation, non-null for the primitive one.
    Generator<?> boxed = ValueGenerators.gen(descriptor.element().naturalClass());
    Generator<?> leaf = leafRepr == LeafRepr.BOXED
        ? Generator.frequency(1, Generator.constant(null), 9, boxed) : boxed;
    fillArray(env, array, lengths, 0, leaf);
    return new FuzzArray(descriptor.oid(), leafRepr, ndim, array);
  }

  /** Fills a rectangular nested array by walking its indices, drawing a leaf value at the deepest level. */
  private static void fillArray(GenerationEnvironment env, Object array, int[] lengths, int depth,
      Generator<?> leaf) {
    int length = lengths[depth];
    if (depth == lengths.length - 1) {
      for (int i = 0; i < length; i++) {
        Array.set(array, i, env.generate(leaf));
      }
    } else {
      for (int i = 0; i < length; i++) {
        fillArray(env, Array.get(array, i), lengths, depth + 1, leaf);
      }
    }
  }

  /**
   * A generator of one row of a composite: each attribute drawn from its field descriptor's leaf
   * generator, in field order. Used for the array-of-record target, so the row shape follows the
   * registered {@link org.postgresql.fuzzkit.coercion.CompositeDescriptor}.
   */
  private static Generator<Object[]> compositeRowGenerator(int compositeOid) {
    List<CompositeDescriptor.Field> fields =
        PgTypeDescriptors.composite(compositeOid).fields();
    return Generator.from(env -> {
      Object[] row = new Object[fields.size()];
      for (int i = 0; i < fields.size(); i++) {
        ScalarDescriptor field = PgTypeDescriptors.scalar(fields.get(i).oid());
        row[i] = ValueGenerators.draw(env, field.naturalClass());
      }
      return row;
    });
  }

  private static byte[] toByteArray(List<Byte> bytes) {
    byte[] result = new byte[bytes.size()];
    for (int i = 0; i < bytes.size(); i++) {
      result[i] = bytes.get(i);
    }
    return result;
  }

  private static FuzzRecord toRecord(List<Object[]> fields) {
    if (fields.isEmpty()) {
      // A record needs at least one attribute; the empty seed would otherwise make a 0-field record.
      return new FuzzRecord(new int[]{Oid.INT4}, new Object[]{0});
    }
    int[] oids = new int[fields.size()];
    Object[] values = new Object[fields.size()];
    for (int i = 0; i < fields.size(); i++) {
      oids[i] = (Integer) fields.get(i)[0];
      values[i] = fields.get(i)[1];
    }
    return new FuzzRecord(oids, values);
  }

  private static FuzzNode toNode(List<FuzzNode.Field> fields, boolean anonymous) {
    if (fields.isEmpty()) {
      // A record needs at least one attribute; the empty seed would otherwise make a 0-field node.
      return new FuzzNode(Collections.singletonList(FuzzNode.Field.scalar(Oid.INT4, 0)), anonymous);
    }
    return new FuzzNode(fields, anonymous);
  }
}
