/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.util;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;

import java.util.regex.Pattern;

/**
 * Provides a matcher for String objects which does a regex comparison.
 */
public final class RegexMatcher extends TypeSafeMatcher<String> {

  private final Pattern pattern;

  /**
   * @param pattern
   *          The pattern to match items on.
   */
  private RegexMatcher(Pattern pattern) {
    this.pattern = pattern;
  }

  public static Matcher<String> matchesPattern(String pattern) {
    return new RegexMatcher(Pattern.compile(pattern));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void describeTo(Description description) {
    description.appendText("matches regex=" + pattern.toString());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected boolean matchesSafely(String item) {
    return pattern.matcher(item).matches();
  }

}
