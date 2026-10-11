/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.arch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.BufferedOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * A stream is reported when the checked method resolves to one of the JDK bases the rule names:
 * {@code available()} to {@link InputStream}, {@code write(byte[], int, int)} to
 * {@link OutputStream} or {@link FilterOutputStream}. A declaration in a class between the stream
 * and that base satisfies the rule, whether the class belongs to the project or to the JDK.
 */
class StreamArchRulesTest {

  static Stream<Arguments> inputStreamsThatInheritAvailable() {
    return Stream.of(
        argumentSet("declares read() only", ReadOnly.class,
            "org.postgresql.test.arch.StreamArchRulesTest$ReadOnly"
                + " inherits java.io.InputStream.available()"),
        argumentSet("extends a project stream that declares read() only", ReadOnlySubclass.class,
            "org.postgresql.test.arch.StreamArchRulesTest$ReadOnlySubclass"
                + " inherits java.io.InputStream.available()"));
  }

  @ParameterizedTest
  @MethodSource("inputStreamsThatInheritAvailable")
  void reportsAnInputStreamWhoseAvailableResolvesToInputStream(Class<?> stream, String report) {
    assertEquals(Collections.singletonList(report),
        reports(StreamArchRules.inputStreamsShouldOverrideAvailable(), stream));
  }

  static Stream<Arguments> inputStreamsThatOverrideAvailable() {
    return Stream.of(
        argumentSet("declares available()", WithAvailable.class),
        argumentSet("extends a project stream that declares available()",
            WithAvailableSubclass.class),
        argumentSet("extends FilterInputStream", FilterInputSubclass.class));
  }

  @ParameterizedTest
  @MethodSource("inputStreamsThatOverrideAvailable")
  void acceptsAnInputStreamWhoseAvailableResolvesBelowInputStream(Class<?> stream) {
    assertEquals(Collections.emptyList(),
        reports(StreamArchRules.inputStreamsShouldOverrideAvailable(), stream));
  }

  static Stream<Arguments> outputStreamsThatInheritPerByteBulkWrite() {
    return Stream.of(
        argumentSet("extends OutputStream and declares write(int) only", WriteOnly.class,
            "org.postgresql.test.arch.StreamArchRulesTest$WriteOnly"
                + " inherits java.io.OutputStream.write(byte[], int, int)"),
        argumentSet("extends a project stream that declares write(int) only",
            WriteOnlySubclass.class,
            "org.postgresql.test.arch.StreamArchRulesTest$WriteOnlySubclass"
                + " inherits java.io.OutputStream.write(byte[], int, int)"),
        argumentSet("extends FilterOutputStream and overrides flush() only", FlushOnly.class,
            "org.postgresql.test.arch.StreamArchRulesTest$FlushOnly"
                + " inherits java.io.FilterOutputStream.write(byte[], int, int)"),
        argumentSet("extends a project stream that inherits it", FlushOnlySubclass.class,
            "org.postgresql.test.arch.StreamArchRulesTest$FlushOnlySubclass"
                + " inherits java.io.FilterOutputStream.write(byte[], int, int)"));
  }

  @ParameterizedTest
  @MethodSource("outputStreamsThatInheritPerByteBulkWrite")
  void reportsAnOutputStreamWhoseBulkWriteResolvesToAPerByteBase(Class<?> stream, String report) {
    assertEquals(Collections.singletonList(report),
        reports(StreamArchRules.outputStreamsShouldOverrideBulkWrite(), stream));
  }

  static Stream<Arguments> outputStreamsThatOverrideBulkWrite() {
    return Stream.of(
        argumentSet("extends FilterOutputStream and declares it", WithBulkWrite.class),
        argumentSet("extends a project stream that declares it", WithBulkWriteSubclass.class),
        argumentSet("extends BufferedOutputStream", BufferedSubclass.class));
  }

  @ParameterizedTest
  @MethodSource("outputStreamsThatOverrideBulkWrite")
  void acceptsAnOutputStreamWhoseBulkWriteResolvesBelowThePerByteBases(Class<?> stream) {
    assertEquals(Collections.emptyList(),
        reports(StreamArchRules.outputStreamsShouldOverrideBulkWrite(), stream));
  }

  @Test
  void skipsInputStreamItself() {
    assertEquals(Collections.emptyList(),
        reports(StreamArchRules.inputStreamsShouldOverrideAvailable(),
            InputStream.class, WithAvailable.class));
  }

  @Test
  void skipsOutputStreamAndFilterOutputStreamThemselves() {
    assertEquals(Collections.emptyList(),
        reports(StreamArchRules.outputStreamsShouldOverrideBulkWrite(),
            OutputStream.class, FilterOutputStream.class, WithBulkWrite.class));
  }

  /**
   * Returns one {@code "<class> inherits <base>.<method>"} entry per violation the rule reports for
   * {@code streams}, without the source location and the rationale that follow it. A report in
   * any other form is returned whole.
   */
  private static List<String> reports(ArchRule rule, Class<?>... streams) {
    List<String> reports = new ArrayList<>();
    for (String detail : rule.evaluate(new ClassFileImporter().importClasses(streams))
        .getFailureReport().getDetails()) {
      int location = detail.indexOf(" in (");
      reports.add(location < 0 ? detail : detail.substring(0, location));
    }
    return reports;
  }

  static class ReadOnly extends InputStream {
    @Override
    public int read() {
      return -1;
    }
  }

  static class ReadOnlySubclass extends ReadOnly {
  }

  static class WithAvailable extends InputStream {
    @Override
    public int read() {
      return -1;
    }

    @Override
    public int available() {
      return 0;
    }
  }

  static class WithAvailableSubclass extends WithAvailable {
  }

  static class FilterInputSubclass extends FilterInputStream {
    FilterInputSubclass(InputStream in) {
      super(in);
    }
  }

  static class WriteOnly extends OutputStream {
    @Override
    public void write(int b) {
    }
  }

  static class WriteOnlySubclass extends WriteOnly {
  }

  static class FlushOnly extends FilterOutputStream {
    FlushOnly(OutputStream out) {
      super(out);
    }

    @Override
    public void flush() {
    }
  }

  static class FlushOnlySubclass extends FlushOnly {
    FlushOnlySubclass(OutputStream out) {
      super(out);
    }
  }

  static class WithBulkWrite extends FilterOutputStream {
    WithBulkWrite(OutputStream out) {
      super(out);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      out.write(b, off, len);
    }
  }

  static class WithBulkWriteSubclass extends WithBulkWrite {
    WithBulkWriteSubclass(OutputStream out) {
      super(out);
    }
  }

  static class BufferedSubclass extends BufferedOutputStream {
    BufferedSubclass(OutputStream out) {
      super(out);
    }
  }
}
