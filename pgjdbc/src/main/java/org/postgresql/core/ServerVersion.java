package org.postgresql.core;

import java.text.NumberFormat;
import java.text.ParsePosition;

/**
 * Enumeration for PostgreSQL versions.
 */
public enum ServerVersion implements Version {

  INVALID("0.0.0"),
  v6_4("6.4.0"),
  v6_5("6.5.0"),
  v7_0("7.0.0"),
  v7_1("7.1.0"),
  v7_2("7.2.0"),
  v7_3("7.3.0"),
  v7_4("7.4.0"),
  v8_0("8.0.0"),
  v8_1("8.1.0"),
  v8_2("8.2.0"),
  v8_3("8.3.0"),
  v8_4("8.4.0"),
  v9_0("9.0.0"),
  v9_1("9.1.0"),
  v9_2("9.2.0"),
  v9_3("9.3.0"),
  v9_4("9.4.0"),
  v9_5("9.5.0"),;

  private final int version;

  private ServerVersion(String version) {
    this.version = parseServerVersionStr(version);
  }

  /**
   * Get a machine-readable version number.
   *
   * @return the version in numeric XXYYZZ form, e.g. 90401 for 9.4.1
   */
  @Override
  public int getVersionNum() {
    return version;
  }

  /**
   * Attempt to parse the server version string into an XXYYZZ form version number into a {@link
   * Version}.
   *
   * If the specified version cannot be parsed, the {@link Version#getVersionNum()} will return 0.
   *
   * @param version version in numeric XXYYZZ form, e.g. "090401" for 9.4.1
   * @return a {@link Version} representing the specified version string.
   */
  public static Version from(String version) {
    final int versionNum = parseServerVersionStr(version);
    return new Version() {
      @Override
      public int getVersionNum() {
        return versionNum;
      }

      @Override
      public boolean equals(Object obj) {
        if (obj instanceof Version) {
          return this.getVersionNum() == ((Version) obj).getVersionNum();
        }
        return false;
      }

      @Override
      public int hashCode() {
        return getVersionNum();
      }

      @Override
      public String toString() {
        return Integer.toString(versionNum);
      }
    };
  }

  /**
   * Attempt to parse the server version string into an XXYYZZ form version number.
   *
   * Returns 0 if the version could not be parsed.
   *
   * Returns minor version 0 if the minor version could not be determined, e.g. devel or beta
   * releases.
   *
   * If a single major part like 90400 is passed, it's assumed to be a pre-parsed version and
   * returned verbatim. (Anything equal to or greater than 10000 is presumed to be this form).
   *
   * The yy or zz version parts may be larger than 99. A NumberFormatException is thrown if a
   * version part is out of range.
   *
   * @param serverVersion server vertion in a XXYYZZ form
   * @return server version in number form
   */
  static int parseServerVersionStr(String serverVersion)
      throws NumberFormatException {
    int vers;
    NumberFormat numformat = NumberFormat.getIntegerInstance();
    numformat.setGroupingUsed(false);
    ParsePosition parsepos = new ParsePosition(0);
    Long parsed;

    if (serverVersion == null) {
      return 0;
    }

    /* Get first major version part */
    parsed = (Long) numformat.parseObject(serverVersion, parsepos);
    if (parsed == null) {
      return 0;
    }
    if (parsed.intValue() >= 10000) {
      /*
       * PostgreSQL version 1000? I don't think so. We're seeing a version like
       * 90401; return it verbatim, but only if there's nothing else in the version.
       * If there is, treat it as a parse error.
       */
      if (parsepos.getIndex() == serverVersion.length()) {
        return parsed.intValue();
      } else {
        throw new NumberFormatException(
            "First major-version part equal to or greater than 10000 in invalid version string: "
                + serverVersion);
      }
    }

    vers = parsed.intValue() * 10000;

    /* Did we run out of string? */
    if (parsepos.getIndex() == serverVersion.length()) {
      return 0;
    }

    /* Skip the . */
    if (serverVersion.charAt(parsepos.getIndex()) == '.') {
      parsepos.setIndex(parsepos.getIndex() + 1);
    } else
      /* Unexpected version format */ {
      return 0;
    }

    /*
     * Get second major version part. If this isn't purely an integer,
     * accept the integer part and return with a minor version of zero,
     * so we cope with 8.1devel, etc.
     */
    parsed = (Long) numformat.parseObject(serverVersion, parsepos);
    if (parsed == null) {
      /*
       * Failed to parse second part of minor version at all. Half
       * a major version is useless, return 0.
       */
      return 0;
    }
    if (parsed.intValue() > 99) {
      throw new NumberFormatException(
          "Unsupported second part of major version > 99 in invalid version string: "
              + serverVersion);
    }
    vers = vers + parsed.intValue() * 100;

    /* Did we run out of string? Return just the major. */
    if (parsepos.getIndex() == serverVersion.length()) {
      return vers;
    }

    /* Skip the . */
    if (serverVersion.charAt(parsepos.getIndex()) == '.') {
      parsepos.setIndex(parsepos.getIndex() + 1);
    } else
      /* Doesn't look like an x.y.z version, return what we have */ {
      return vers;
    }

    /* Try to parse any remainder as a minor version */
    parsed = (Long) numformat.parseObject(serverVersion, parsepos);
    if (parsed != null) {
      if (parsed.intValue() > 99) {
        throw new NumberFormatException(
            "Unsupported minor version value > 99 in invalid version string: " + serverVersion);
      }
      vers = vers + parsed.intValue();
    }

    return vers;
  }

}
