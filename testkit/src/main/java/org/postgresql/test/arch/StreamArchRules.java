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
 * Architecture rules for {@link InputStream} and {@link OutputStream} implementations, which any
 * module can apply to its own classes. A module applies them by importing its production classes and calling
 * {@link ArchRule#check(com.tngtech.archunit.core.domain.JavaClasses)}, and keeps its own list of
 * the classes that do not comply yet.
 *
 * <p>Each rule checks which class a method resolves to rather than whether the class declares it,
 * so a decorator that inherits a suitable implementation passes without declaring the method
 * again. Which JDK implementations count as suitable differs per method:
 * {@link java.io.FilterInputStream#available()} returns what the wrapped stream reports, while
 * {@link FilterOutputStream#write(byte[], int, int)} writes the range one byte per call.</p>
 *
 * <p>There is no rule for {@code read(byte[], int, int)}. When production code compiles with Error
 * Prone, its {@code InputStreamSlowMultibyteRead} check reports a stream that declares
 * {@code read()} without {@code read(byte[], int, int)}, unless that {@code read()} returns a
 * constant.</p>
 */
public final class StreamArchRules {
  private static final String INPUT_STREAM = InputStream.class.getName();

  /**
   * Bases whose {@code write(byte[], int, int)} a subclass should not inherit.
   * {@link FilterOutputStream} is on the list because it writes one byte per call, so a decorator
   * that does not override the method passes each bulk write to the next stream one byte per call.
   */
  private static final Collection<String> UNSUITABLE_BULK_WRITE_BASES = Arrays.asList(
      OutputStream.class.getName(), FilterOutputStream.class.getName());

  private StreamArchRules() {
  }

  /**
   * Returns a rule that reports every {@link InputStream} subclass whose {@code available()}
   * resolves to {@link InputStream#available()}, which always returns 0.
   *
   * @return the rule, ready to check against a set of imported classes
   */
  public static ArchRule inputStreamsShouldOverrideAvailable() {
    return classes()
        .that().areAssignableTo(InputStream.class)
        .and().doNotHaveFullyQualifiedName(INPUT_STREAM)
        .should(overrideMethod(Arrays.asList(INPUT_STREAM), "available",
            "java.io.InputStream.available() always returns 0, so callers that size a buffer or "
                + "poll for buffered input see an empty stream. Return the number of bytes that "
                + "can be read without blocking."))
        .as("InputStream implementations should override available()")
        .because("available() is the only InputStream method with which a caller can tell buffered "
            + "bytes from an empty stream without blocking on read()");
  }

  /**
   * Returns a rule that reports every {@link OutputStream} subclass other than
   * {@link FilterOutputStream} itself whose {@code write(byte[], int, int)} resolves to {@link OutputStream} or {@link FilterOutputStream},
   * both of which write the range one byte per call.
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
                + "write one byte per call, which turns a bulk write into a per-byte loop. Write "
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
   * of the unsuitable base the class ends up inheriting it from, or {@code null} when a declaration
   * in a class below that base comes first.
   */
  private static @Nullable String findUnsuitableBase(JavaClass start,
      Collection<String> unsuitableBases, String name, Class<?>... parameters) {
    for (JavaClass current = start; ; ) {
      String className = current.getName();
      if (unsuitableBases.contains(className)) {
        return className;
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
