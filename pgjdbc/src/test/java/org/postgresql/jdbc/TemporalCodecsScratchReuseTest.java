/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.postgresql.api.codec.CodecContext;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;

/**
 * Guards the thread-confined scratch {@link Calendar} that {@link TemporalCodecs} reuses on the
 * binary {@code timestamp} decode path.
 *
 * <p>The scratch is only consulted for non-simple (DST) zones, where {@code guessTimestamp} composes
 * the value field by field through a {@link Calendar}. Reusing one calendar across decodes must not
 * change the result versus allocating a fresh calendar each time, and must apply the zone's
 * <em>historical</em> offset rather than today's. Both properties matter for a zone such as
 * {@code Europe/Moscow}, which no longer observes DST but still carries the 2011 and 2014 permanent
 * shifts, so its offset in 2013 (UTC+4) differs from 2015 (UTC+3).</p>
 *
 * <p>The reference is {@link TimestampUtils#toTimestampBin} with a {@code null} scratch, which
 * allocates a fresh {@link GregorianCalendar} per call — the behaviour before the scratch was
 * reused.</p>
 */
class TemporalCodecsScratchReuseTest {

  // usesDouble=false (integer datetimes); the session zone here only feeds encode, which is
  // zone-independent for a LocalDateTime.
  private static final CodecContext ENCODE_CTX = TestCodecContext.create();

  private static final TimeZone BERLIN = TimeZone.getTimeZone("Europe/Berlin");
  private static final TimeZone MOSCOW = TimeZone.getTimeZone("Europe/Moscow");

  /** Encodes {@code wallClock} as an 8-byte binary {@code timestamp} (no zone). */
  private static byte[] encode(LocalDateTime wallClock) throws SQLException {
    return TemporalCodecs.encodeTimestampBin(wallClock, ENCODE_CTX);
  }

  /** Decodes through {@link TemporalCodecs}, which reuses the thread-confined scratch calendar. */
  private static Timestamp decodeViaCodec(LocalDateTime wallClock, TimeZone zone)
      throws SQLException {
    PgCodecContext ctx = ((PgCodecContext) TestCodecContext.create())
        .withCalendar(new GregorianCalendar(zone));
    byte[] wire = encode(wallClock);
    Timestamp value = TemporalCodecs.decodeTimestampBin(wire, 0, wire.length, false, ctx);
    assert value != null;
    return value;
  }

  /** Decodes with a {@code null} scratch, so a fresh {@link GregorianCalendar} is allocated. */
  private static Timestamp decodeWithFreshCalendar(LocalDateTime wallClock, TimeZone zone)
      throws SQLException {
    byte[] wire = encode(wallClock);
    return TimestampUtils.toTimestampBin(false, zone, null, wire, 0, wire.length, false);
  }

  @Test
  void appliesHistoricalOffsetPerZone() throws SQLException {
    // Berlin observes DST: winter is UTC+1, summer UTC+2.
    assertInstant("2023-01-01T11:00:00Z", LocalDateTime.of(2023, 1, 1, 12, 0), BERLIN);
    assertInstant("2023-07-01T10:00:00Z", LocalDateTime.of(2023, 7, 1, 12, 0), BERLIN);
    // Moscow stopped observing DST, but the historical offset still changes across the 2014 shift:
    // UTC+4 in 2013 (permanent-DST era), UTC+3 in 2015. Decoding both in one run also exercises the
    // reused scratch across two different offsets.
    assertInstant("2013-01-01T08:00:00Z", LocalDateTime.of(2013, 1, 1, 12, 0), MOSCOW);
    assertInstant("2015-01-01T09:00:00Z", LocalDateTime.of(2015, 1, 1, 12, 0), MOSCOW);
  }

  @Test
  void matchesFreshCalendarAroundTransitions() throws SQLException {
    // Wall clocks straddling spring-forward gaps and fall-back overlaps, including the Moscow
    // permanent transitions. The zones alternate so each decode reuses a scratch left dirty by the
    // previous zone. GregorianCalendar defines how a gap or overlap resolves; the codec must match
    // the fresh-calendar reference exactly.
    LocalDateTime[][] cases = {
        // Berlin spring forward 2023-03-26 02:00 -> 03:00 (02:30 is in the gap).
        {LocalDateTime.of(2023, 3, 26, 1, 30)}, {LocalDateTime.of(2023, 3, 26, 2, 30)},
        {LocalDateTime.of(2023, 3, 26, 3, 30)},
        // Berlin fall back 2023-10-29 03:00 -> 02:00 (02:30 is ambiguous).
        {LocalDateTime.of(2023, 10, 29, 1, 30)}, {LocalDateTime.of(2023, 10, 29, 2, 30)},
        {LocalDateTime.of(2023, 10, 29, 3, 30)},
    };
    LocalDateTime[][] moscowCases = {
        // Moscow spring forward to permanent DST 2011-03-27 02:00 -> 03:00.
        {LocalDateTime.of(2011, 3, 27, 1, 30)}, {LocalDateTime.of(2011, 3, 27, 2, 30)},
        {LocalDateTime.of(2011, 3, 27, 3, 30)},
        // Moscow fall back to permanent standard time 2014-10-26 02:00 -> 01:00.
        {LocalDateTime.of(2014, 10, 26, 0, 30)}, {LocalDateTime.of(2014, 10, 26, 1, 30)},
        {LocalDateTime.of(2014, 10, 26, 2, 30)},
    };
    for (int i = 0; i < cases.length; i++) {
      LocalDateTime berlin = cases[i][0];
      LocalDateTime moscow = moscowCases[i][0];
      assertEquals(decodeWithFreshCalendar(berlin, BERLIN), decodeViaCodec(berlin, BERLIN),
          "Europe/Berlin " + berlin);
      assertEquals(decodeWithFreshCalendar(moscow, MOSCOW), decodeViaCodec(moscow, MOSCOW),
          "Europe/Moscow " + moscow);
    }
  }

  @Test
  void reusedScratchDoesNotBleedBetweenDecodes() throws SQLException {
    // Decode a Moscow fall-back overlap, then a Berlin value that mutates the shared calendar to a
    // different zone and fields, then the Moscow value again: the second Moscow result must equal the
    // first, proving the intervening decode left no state behind.
    LocalDateTime moscow = LocalDateTime.of(2014, 10, 26, 1, 30);
    LocalDateTime berlin = LocalDateTime.of(2023, 7, 1, 12, 0);

    Timestamp first = decodeViaCodec(moscow, MOSCOW);
    decodeViaCodec(berlin, BERLIN);
    Timestamp again = decodeViaCodec(moscow, MOSCOW);

    assertEquals(first, again);
    assertEquals(decodeWithFreshCalendar(moscow, MOSCOW), again);
  }

  private static void assertInstant(String expectedIso, LocalDateTime wallClock, TimeZone zone)
      throws SQLException {
    long expectedMillis = Instant.parse(expectedIso).toEpochMilli();
    assertEquals(expectedMillis, decodeViaCodec(wallClock, zone).getTime(),
        zone.getID() + " " + wallClock);
  }
}
