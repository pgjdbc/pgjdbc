package org.postgresql.util;

import java.text.MessageFormat;
import java.util.ResourceBundle;
import java.util.MissingResourceException;

public class GT {

	private static GT instance;

	private final static GT getGT() {
		if (instance == null) {
			instance = new GT();
		}
		return instance;
	}

	public final static String tr(String message) {
		GT gt = getGT();
		return gt.translate(message, null);
	}

	public final static String tr(String message, Object arg) {
		GT gt = getGT();
		return gt.translate(message, new Object[]{arg});
	}

	public final static String tr(String message, Object args[]) {
		GT gt = getGT();
		return gt.translate(message, args);
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

