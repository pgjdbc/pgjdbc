/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Edge-case {@code json}/{@code jsonb} documents (the same literals serve both types): empty containers,
 * the scalar forms, nesting, a unicode escape, and the whitespace/duplicate-key/key-order shapes where
 * {@code jsonb} normalises but {@code json} keeps the text verbatim.
 *
 * <p>Read-only ({@link EdgeCase#value()} is {@code null}). None of the literals contain a single quote, so
 * they drop straight into a {@code '...'::json} cast.
 */
public final class JsonEdgeCases {
  /** Every case, in a stable order. */
  public static final List<EdgeCase> ALL = Collections.unmodifiableList(all());

  private JsonEdgeCases() {
  }

  private static List<EdgeCase> all() {
    List<EdgeCase> out = new ArrayList<>();
    out.add(at("empty_object", "{}"));
    out.add(at("empty_array", "[]"));
    out.add(at("json_null", "null"));
    out.add(at("boolean_true", "true"));
    out.add(at("number", "1.5"));
    out.add(at("string_scalar", "\"hello\""));
    out.add(at("nested", "{\"a\":[1,2,{\"b\":null}]}"));
    out.add(at("unicode_escape", "{\"e\":\"\\u00e9\"}"));
    out.add(at("whitespace", "{ \"a\" : 1 }"));
    out.add(at("duplicate_keys", "{\"a\":1,\"a\":2}"));
    out.add(at("big_integer", "12345678901234567890"));
    // Deep and large structures stress the parser/decoder's recursion and buffering.
    out.add(at("deep_nested_objects", nestedObjects(64)));
    out.add(at("deep_nested_arrays", nestedArrays(64)));
    out.add(at("long_array", longArray(1000)));
    out.add(at("many_keys", manyKeys(200)));
    out.add(at("long_string", longString(20000)));
    return out;
  }

  private static String nestedObjects(int depth) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < depth; i++) {
      sb.append("{\"a\":");
    }
    sb.append("null");
    for (int i = 0; i < depth; i++) {
      sb.append('}');
    }
    return sb.toString();
  }

  private static String nestedArrays(int depth) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < depth; i++) {
      sb.append('[');
    }
    sb.append('0');
    for (int i = 0; i < depth; i++) {
      sb.append(']');
    }
    return sb.toString();
  }

  private static String longArray(int count) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < count; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append(i);
    }
    return sb.append(']').toString();
  }

  private static String manyKeys(int count) {
    StringBuilder sb = new StringBuilder("{");
    for (int i = 0; i < count; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append("\"k").append(i).append("\":").append(i);
    }
    return sb.append('}').toString();
  }

  private static String longString(int length) {
    StringBuilder sb = new StringBuilder(length + 2).append('"');
    for (int i = 0; i < length; i++) {
      sb.append('a');
    }
    return sb.append('"').toString();
  }

  private static EdgeCase at(String name, String literal) {
    return new EdgeCase(name, literal, null);
  }
}
