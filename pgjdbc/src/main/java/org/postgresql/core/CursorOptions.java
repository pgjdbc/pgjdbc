package org.postgresql.core;

/**
 * Cursor options for extended query protocol.
 * These match the PostgreSQL backend cursor options bitmask.
 */
public final class CursorOptions {
  
  /** BINARY cursor */
  public static final int BINARY = 0x0001;
  
  /** SCROLL cursor */
  public static final int SCROLL = 0x0002;
  
  /** NO SCROLL cursor */
  public static final int NO_SCROLL = 0x0004;
  
  /** INSENSITIVE cursor */
  public static final int INSENSITIVE = 0x0008;
  
  /** ASENSITIVE cursor */
  public static final int ASENSITIVE = 0x0010;
  
  /** WITH HOLD cursor - survives transaction commit */
  public static final int HOLD = 0x0020;
  
  /** Prefer fast-start plan */
  public static final int FAST_PLAN = 0x0100;
  
  /** Force generic plan */
  public static final int GENERIC_PLAN = 0x0200;
  
  /** Force custom plan */
  public static final int CUSTOM_PLAN = 0x0400;
  
  /** Allow parallel workers */
  public static final int PARALLEL_OK = 0x0800;
  
  private CursorOptions() {
    // Utility class
  }
}
