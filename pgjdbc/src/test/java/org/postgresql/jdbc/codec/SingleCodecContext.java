/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.Codec;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.jdbc.TestCodecContext;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.nio.charset.Charset;
import java.sql.SQLException;
import java.util.Calendar;
import java.util.Map;
import java.util.TimeZone;

/**
 * A {@link CodecContext} that resolves every OID to one codec and delegates the wire/policy surface
 * to a connectionless test context. Lets a test drive {@link org.postgresql.api.codec.Codecs}
 * against a specific codec without a live registry or connection.
 */
final class SingleCodecContext implements CodecContext {
  private final Codec codec;
  private final CodecContext delegate = TestCodecContext.create();

  SingleCodecContext(Codec codec) {
    this.codec = codec;
  }

  @Override
  public Codec resolveCodec(int oid) {
    return codec;
  }

  @Override
  public TypeDescriptor resolveType(int oid) throws SQLException {
    return delegate.resolveType(oid);
  }

  @Override
  public CodecContext withoutJavaTimePreferences() {
    return this;
  }

  @Override
  public Charset getCharset() {
    return delegate.getCharset();
  }

  @Override
  public boolean usesDoubleDateTime() {
    return delegate.usesDoubleDateTime();
  }

  @Override
  public TimeZone getClientTimeZone() {
    return delegate.getClientTimeZone();
  }

  @Override
  public TimeZone getDefaultTimeZone() {
    return delegate.getDefaultTimeZone();
  }

  @Override
  public @Nullable Calendar getCalendar() {
    return delegate.getCalendar();
  }

  @Override
  public boolean prefersJavaTimeForDate() {
    return delegate.prefersJavaTimeForDate();
  }

  @Override
  public boolean prefersJavaTimeForTime() {
    return delegate.prefersJavaTimeForTime();
  }

  @Override
  public boolean prefersJavaTimeForTimetz() {
    return delegate.prefersJavaTimeForTimetz();
  }

  @Override
  public boolean prefersJavaTimeForTimestamp() {
    return delegate.prefersJavaTimeForTimestamp();
  }

  @Override
  public boolean prefersJavaTimeForTimestamptz() {
    return delegate.prefersJavaTimeForTimestamptz();
  }

  @Override
  public boolean getConvertBooleanToNumeric() {
    return delegate.getConvertBooleanToNumeric();
  }

  @Override
  public Map<String, Class<?>> getTypeMap() {
    return delegate.getTypeMap();
  }

  @Override
  public @Nullable Class<?> getMappedClass(String typeName) {
    return delegate.getMappedClass(typeName);
  }
}
