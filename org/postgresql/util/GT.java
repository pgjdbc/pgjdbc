/*-------------------------------------------------------------------------
 *
 * Copyright (c) 2004, PostgreSQL Global Development Group
 *
 * IDENTIFICATION
 *	  $PostgreSQL$
 *
 *-------------------------------------------------------------------------
 */
package org.postgresql.util;

import java.text.MessageFormat;
import java.util.ResourceBundle;
import java.util.MissingResourceException;

/**
 * This class provides a wrapper around a gettext message catalog that
 * can provide a localized version of error messages.  The caller provides
 * a message String in the standard java.text.MessageFormat syntax and any
 * arguments it may need.  The returned String is the localized version if
 * available or the original if not.
 */

public class GT {

	private static GT _gt = new GT();

	public final static String tr(String message) {
		return _gt.translate(message, null);
	}

	public final static String tr(String message, Object arg) {
		return _gt.translate(message, new Object[]{arg});
	}

	public final static String tr(String message, Object args[]) {
		return _gt.translate(message, args);
	}


	private ResourceBundle _bundle;

	private GT() {
		try {
			_bundle = ResourceBundle.getBundle("org.postgresql.translation.messages");
		} catch (MissingResourceException mre) {
			// translation files have not been installed
			_bundle = null;
		}
	}

	private final String translate(String message, Object args[])
	{
		if (_bundle != null && message != null)
		{
			try {
				message = _bundle.getString(message);
			} catch (MissingResourceException mre) {
				// If we can't find a translation, just
				// use the untranslated message.
			}
		}

		// Replace placeholders with arguments
		//
		if (args != null && message != null) {
			message = MessageFormat.format(message, args);
		}

		return message;
	}

}

