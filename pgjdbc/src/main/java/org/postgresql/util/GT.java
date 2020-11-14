/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.dataflow.qual.Pure;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * This class provides a wrapper around a gettext message catalog that can provide a localized
 * version of error messages. The caller provides a message String in the standard
 * java.text.MessageFormat syntax and any arguments it may need. The returned String is the
 * localized version if available or the original if not.
 */
public class GT {

  private static final GT _gt = new GT();
  private static final Object[] noargs = new Object[0];

  public static @Pure String tr(String message, @Nullable Object... args) {
    return _gt.translate(message, args);
  }

  private @Nullable ResourceBundle bundle;

  private GT() {
    try {
      bundle = ResourceBundle.getBundle("org.postgresql.translation.messages", Locale.getDefault(Locale.Category.DISPLAY));
    } catch (MissingResourceException mre) {
      // translation files have not been installed
      bundle = null;
    }
  }

  private String translate(String message, @Nullable Object[] args) {
    if (bundle != null && message != null) {
      try {
        message = bundle.getString(message);
      } catch (MissingResourceException mre) {
        // If we can't find a translation, just
        // use the untranslated message.
      }
    }

    // If we don't have any parameters we still need to run
    // this through the MessageFormat(ter) to allow the same
    // quoting and escaping rules to be used for all messages.
    //
    if (args == null) {
      args = noargs;
    }

    // Replace placeholders with arguments
    //
    if (message != null) {
      message = MessageFormat.format(message, args);
    }

    return message;
  }
}
