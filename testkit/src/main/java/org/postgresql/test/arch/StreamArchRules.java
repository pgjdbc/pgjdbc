/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.FilterOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;

/**
 * Architecture rules for {@link InputStream} and {@link OutputStream} implementations, shared by
 * every module that has some. A module applies them by importing its production classes and calling
 * {@link ArchRule#check(com.tngtech.archunit.core.domain.JavaClasses)}, and keeps its own list of
 * the classes that do not comply yet.
 *
 * <p>Each rule asks which class a method resolves to rather than whether the class declares it, so
 * a decorator that inherits a suitable implementation passes without restating it. Which JDK
 * implementations count as suitable differs per method: {@link java.io.FilterInputStream} hands a
 * bulk read straight to the stream it wraps, while {@link FilterOutputStream} splits a bulk write
 * into one call per byte.</p>
 */
public final class StreamArchRules {
  private static final String INPUT_STREAM = InputStream.class.getName();

  /**
   * Bases whose {@code write(byte[], int, int)} a subclass should not inherit.
   * {@link FilterOutputStream} is on the list because it forwards one byte per call, so a decorator
   * that leaves it alone turns every bulk write into a per-byte loop through the whole chain.
   */
  private static final Collection<String> UNSUITABLE_BULK_WRITE_BASES = Arrays.asList(
      OutputStream.class.getName(), FilterOutputStream.class.getName());

  private StreamArchRules() {
  }

  /**
   * Requires every {@link InputStream} to answer {@code available()} with something other than the
   * constant zero inherited from {@link InputStream}.
   *
   * @return the rule, ready to check against a set of imported classes
   */
  public static ArchRule inputStreamsShouldOverrideAvailable() {
    return classes()
        .that().areAssignableTo(InputStream.class)
        .and().doNotHaveFullyQualifiedName(INPUT_STREAM)
        .should(overrideMethod(Arrays.asList(INPUT_STREAM), "available",
            "java.io.InputStream.available() always returns 0, so callers that size a buffer or "
                + "poll for buffered input see an empty stream. Return the number of bytes the "
                + "stream can hand out without blocking."))
        .as("InputStream implementations should override available()")
        .because("available() is the only way a caller can tell buffered bytes from an empty "
            + "stream without blocking on read()");
  }

  /**
   * Requires every {@link InputStream} to answer {@code read(byte[], int, int)} with something
   * other than the per-byte loop inherited from {@link InputStream}.
   *
   * @return the rule, ready to check against a set of imported classes
   */
  public static ArchRule inputStreamsShouldOverrideBulkRead() {
    return classes()
        .that().areAssignableTo(InputStream.class)
        .and().doNotHaveFullyQualifiedName(INPUT_STREAM)
        .should(overrideMethod(Arrays.asList(INPUT_STREAM), "read",
            "java.io.InputStream.read(byte[], int, int) copies one byte per read() call, which "
                + "turns a bulk read into a per-byte loop. Copy from the underlying buffer or "
                + "source in one step.",
            byte[].class, int.class, int.class))
        .as("InputStream implementations should override read(byte[], int, int)")
        .because("a bulk read that degrades to one call per byte is invisible at the call site");
  }

  /**
   * Requires every {@link OutputStream} to answer {@code write(byte[], int, int)} with something
   * other than the per-byte loop inherited from {@link OutputStream} or {@link FilterOutputStream}.
   *
   * @return the rule, ready to check against a set of imported classes
   */
  public static ArchRule outputStreamsShouldOverrideBulkWrite() {
    return classes()
        .that().areAssignableTo(OutputStream.class)
        .and().doNotHaveFullyQualifiedName(OutputStream.class.getName())
        .and().doNotHaveFullyQualifiedName(FilterOutputStream.class.getName())
        .should(overrideMethod(UNSUITABLE_BULK_WRITE_BASES, "write",
            "both java.io.OutputStream.write(byte[], int, int) and its FilterOutputStream override "
                + "forward one byte per call, which turns a bulk write into a per-byte loop. Write "
                + "the range to the underlying buffer or sink in one step.",
            byte[].class, int.class, int.class))
        .as("OutputStream implementations should override write(byte[], int, int)")
        .because("a bulk write that degrades to one call per byte is invisible at the call site");
  }

  private static ArchCondition<JavaClass> overrideMethod(Collection<String> unsuitableBases,
      String name, String rationale, Class<?>... parameters) {
    String signature = name + '(' + describe(parameters) + ')';
    return new ArchCondition<JavaClass>("override " + signature) {
      @Override
      public void check(JavaClass item, ConditionEvents events) {
        String inheritedFrom = findUnsuitableBase(item, unsuitableBases, name, parameters);
        if (inheritedFrom != null) {
          events.add(SimpleConditionEvent.violated(item,
              item.getName() + " inherits " + inheritedFrom + '.' + signature + " in "
                  + item.getSourceCodeLocation() + ". " + rationale));
        }
      }
    };
  }

  /**
   * Walks up from {@code start} looking for a declaration of the given method, and returns the name
   * of the unsuitable base the class ends up inheriting it from, or {@code null} when a suitable
   * declaration comes first. A JDK class outside {@code unsuitableBases} counts as a suitable
   * declaration: the importer sees such classes as stubs with no methods, and the JDK streams that
   * sit between a subclass and its base carry their own implementation.
   */
  private static @Nullable String findUnsuitableBase(JavaClass start,
      Collection<String> unsuitableBases, String name, Class<?>... parameters) {
    for (JavaClass current = start; ; ) {
      String className = current.getName();
      if (unsuitableBases.contains(className)) {
        return className;
      }
      if (className.startsWith("java.") || className.startsWith("javax.")) {
        return null;
      }
      if (current.tryGetMethod(name, parameters).isPresent()) {
        return null;
      }
      Optional<JavaClass> superclass = current.getRawSuperclass();
      if (!superclass.isPresent()) {
        return null;
      }
      current = superclass.get();
    }
  }

  private static String describe(Class<?>... parameters) {
    StringBuilder sb = new StringBuilder();
    for (Class<?> parameter : parameters) {
      if (sb.length() > 0) {
        sb.append(", ");
      }
      sb.append(parameter.getSimpleName());
    }
    return sb.toString();
  }
}
