/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.fuzz;

import org.postgresql.fuzzkit.FuzzText;
import org.postgresql.fuzzkit.coercion.PgTypeDescriptors;
import org.postgresql.fuzzkit.coercion.ScalarDescriptor;
import org.postgresql.fuzzkit.coercion.WriteCoercions.Method;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.jetbrains.jetCheck.GenerationEnvironment;
import org.jetbrains.jetCheck.Generator;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URL;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The one registry of {@code Class -> Generator<value>} for the coercion fuzz matrix. Every "give me a
 * value of class X" question the matrix asks -- the diagonal value for a descriptor's
 * {@code naturalClass}, the value a write {@link Method} accepts, the free-class axis of
 * {@code writeObject} -- resolves to a single lookup here, so the class-to-generator mapping lives in
 * one place instead of the switch chains it replaces.
 *
 * <p>The registry values are the coercion suite's shared scalar generators (from
 * {@link PgValueArgumentsFactory}) keyed by class, plus a few pure {@code map}/{@code zipWith}
 * derivations of them for the free-class axis ({@code LocalDate} from the {@code Date} generator,
 * {@code BigInteger} and {@code UUID} from the long generator, and so on). No generator is invented:
 * each key reuses the exact generator its consumer drew before, so the value distribution is
 * unchanged.
 *
 * <p>Guard G4 runs in the static initialiser: every scalar {@code naturalClass} in
 * {@link PgTypeDescriptors} and every non-null {@link Method#inputClass()} must have a generator here.
 * A missing entry fails class initialisation rather than leaving a silent hole in the matrix.
 */
final class ValueGenerators {

  private static final Generator<Float> FLOATS = Generator.doubles().map(Double::floatValue);
  private static final Generator<Byte> BYTES =
      Generator.integers(Byte.MIN_VALUE, Byte.MAX_VALUE).map(Integer::byteValue);
  private static final Generator<Short> SHORTS =
      Generator.integers(Short.MIN_VALUE, Short.MAX_VALUE).map(Integer::shortValue);
  // Full-BMP Unicode below the surrogate block (U+0000..U+D7FF), so every string is well-formed and
  // round-trips through the text codec; FuzzText.stripNul drops NUL, which PostgreSQL text cannot carry.
  // This mirrors the Jazzer front-end's Unicode string, reaching the multi-byte UTF-8 paths the former
  // printable-ASCII generator never did. Lone surrogates are excluded (not merely stripped of NUL) because
  // STRINGS also feeds the round-trip and writer fuzzers, where a lone surrogate would encode to '?' and
  // break write->read equality.
  private static final Generator<String> STRINGS =
      Generator.stringsOf(Generator.charsInRange((char) 0, (char) 0xD7FF)).map(FuzzText::stripNul);

  private static final Map<Class<?>, Generator<?>> REGISTRY = buildRegistry();

  /**
   * The classes the free-class axis draws from: every registered key. {@code writeObject(Object,
   * SQLType)} and {@code setObject} present the value's own class, so any class with a generator can be
   * fuzzed through them -- "there is a generator, so the class is fuzzed", the target from the design.
   * This is a superset of the classes the former hand-written switch drew, so the axis is not narrowed.
   */
  private static final List<Class<?>> FREE_CLASSES =
      Collections.unmodifiableList(new ArrayList<>(REGISTRY.keySet()));

  static {
    guardGenerators();
  }

  private ValueGenerators() {
  }

  private static Map<Class<?>, Generator<?>> buildRegistry() {
    Map<Class<?>, Generator<?>> map = new LinkedHashMap<>();
    map.put(Integer.class, Generator.integers());
    map.put(Long.class, PgValueArgumentsFactory.LONGS);
    map.put(Float.class, FLOATS);
    map.put(Double.class, Generator.doubles());
    map.put(Boolean.class, Generator.booleans());
    map.put(String.class, STRINGS);
    map.put(BigDecimal.class, PgValueArgumentsFactory.NUMERIC);
    map.put(byte[].class, PgValueArgumentsFactory.BYTEA);
    map.put(Byte.class, BYTES);
    map.put(Short.class, SHORTS);
    map.put(URL.class, Generator.constant(PgValueArgumentsFactory.SAMPLE_URL));
    map.put(Date.class, PgValueArgumentsFactory.DATES);
    map.put(Time.class, PgValueArgumentsFactory.TIMES);
    map.put(Timestamp.class, PgValueArgumentsFactory.TIMESTAMPS);
    map.put(OffsetTime.class, PgValueArgumentsFactory.OFFSET_TIMES);
    map.put(OffsetDateTime.class, PgValueArgumentsFactory.OFFSET_DATE_TIMES);
    // Free-class axis: pure map/zipWith derivations of the shared generators, matching the values the
    // former freeValue switch built inline.
    map.put(LocalDate.class, PgValueArgumentsFactory.DATES.map(Date::toLocalDate));
    map.put(LocalTime.class, PgValueArgumentsFactory.TIMES.map(Time::toLocalTime));
    map.put(LocalDateTime.class, PgValueArgumentsFactory.TIMESTAMPS.map(Timestamp::toLocalDateTime));
    map.put(ZonedDateTime.class,
        PgValueArgumentsFactory.OFFSET_DATE_TIMES.map(OffsetDateTime::toZonedDateTime));
    map.put(Instant.class, PgValueArgumentsFactory.OFFSET_DATE_TIMES.map(OffsetDateTime::toInstant));
    map.put(BigInteger.class, PgValueArgumentsFactory.LONGS.map(BigInteger::valueOf));
    map.put(UUID.class,
        Generator.zipWith(PgValueArgumentsFactory.LONGS, PgValueArgumentsFactory.LONGS,
            (high, low) -> new UUID(high, low)));
    map.put(java.util.Date.class, PgValueArgumentsFactory.LONGS.map(java.util.Date::new));
    return Collections.unmodifiableMap(map);
  }

  /**
   * The generator for a class.
   *
   * @param cls the value class
   * @return its generator
   * @throws IllegalArgumentException if no generator is registered for the class
   */
  static Generator<?> gen(Class<?> cls) {
    Generator<?> generator = REGISTRY.get(cls);
    if (generator == null) {
      throw new IllegalArgumentException("no value generator for " + cls.getName());
    }
    return generator;
  }

  /**
   * Draws a value of the given class from the guided stream.
   *
   * @param env the generation environment
   * @param cls the value class
   * @return a value of {@code cls}
   */
  static Object draw(GenerationEnvironment env, Class<?> cls) {
    return env.generate(gen(cls));
  }

  /**
   * Draws the value a write {@link Method} takes. {@link Method#WRITE_OBJECT_AS} draws the free-class
   * axis; a {@code NOT_IMPLEMENTED} method reads no value, so it draws {@code null}; every other method
   * draws a value of its {@link Method#inputClass()}.
   *
   * @param env the generation environment
   * @param method the write method
   * @return a value the method accepts, or {@code null} for a {@code NOT_IMPLEMENTED} method
   */
  static @Nullable Object writeValue(GenerationEnvironment env, Method method) {
    if (method == Method.WRITE_OBJECT_AS) {
      return freeValue(env);
    }
    Class<?> inputClass = method.inputClass();
    return inputClass == null ? null : draw(env, inputClass);
  }

  /**
   * Draws a value of a randomly chosen registered class -- the free-class axis reached only through
   * {@code writeObject(Object, SQLType)} and {@code setObject}.
   *
   * @param env the generation environment
   * @return a value of some registered class
   */
  static Object freeValue(GenerationEnvironment env) {
    Class<?> cls = FREE_CLASSES.get(env.generate(Generator.integers(0, FREE_CLASSES.size() - 1)));
    return draw(env, cls);
  }

  private static void guardGenerators() {
    // G4: every class the matrix asks for a value of must have a generator, or the matrix has a silent
    // hole. The two demands are a scalar descriptor's naturalClass (the diagonal value) and a write
    // method's inputClass (the value the method accepts).
    for (ScalarDescriptor descriptor : PgTypeDescriptors.scalars()) {
      if (!REGISTRY.containsKey(descriptor.naturalClass())) {
        throw new ExceptionInInitializerError("ValueGenerators has no generator for naturalClass "
            + descriptor.naturalClass().getName() + " (OID " + descriptor.oid() + ")");
      }
    }
    for (Method method : Method.values()) {
      Class<?> inputClass = method.inputClass();
      if (inputClass != null && !REGISTRY.containsKey(inputClass)) {
        throw new ExceptionInInitializerError("ValueGenerators has no generator for inputClass "
            + inputClass.getName() + " of " + method);
      }
    }
  }
}
