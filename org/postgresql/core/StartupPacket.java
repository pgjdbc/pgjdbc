package org.postgresql.core;

import java.io.IOException;
import java.util.Hashtable;
import java.util.Enumeration;

/**
 * Sent to the backend to initialize a newly created connection.
 *
 * $PostgreSQL: StartupPacket.java,v 1.4 2003/05/29 03:21:32 barry Exp $
 */

public class StartupPacket
{
	private static final int SM_DATABASE = 64;
	private static final int SM_USER = 32;
	private static final int SM_OPTIONS = 64;
	private static final int SM_UNUSED = 64;
	private static final int SM_TTY = 64;

	private int protocolMajor;
	private int protocolMinor;

	/*
	 * Extra params can be sent to the server starting with the V3 wire
	 * protocol.  This Hashtable holds these extra parameters.
	 *
	 * Changed by Chris Smith <cdsmith@twu.net>
	 */
	private Hashtable params;

	public StartupPacket(int protocolMajor, int protocolMinor,
	    String user, String database)
	{
		this(protocolMajor, protocolMinor, user, database, new Hashtable());
	}

	public StartupPacket(int protocolMajor, int protocolMinor,
		String user, String database, Hashtable params)
	{
		/*
		 * The extra arguments from this constructor are only
		 * available in v3 of the wire protocol.
		 */
		if ((protocolMajor < 3) && !params.isEmpty())
		{
			throw new IllegalArgumentException();
		}

		this.protocolMajor = protocolMajor;
		this.protocolMinor = protocolMinor;

		params.put("user", user);
		params.put("database", database);

		this.params = params;
	}

	public void writeTo(PGStream stream) throws IOException
	{
		if (protocolMajor == 3) {
			v3WriteTo(stream);
		} else {
			v2WriteTo(stream);
		}
	}

	public void v3WriteTo(PGStream stream) throws IOException
	{
		/*
		 * Precalculate message length.
		 */
		int length = 4 + 4;

		Enumeration en = params.keys();
		while (en.hasMoreElements())
		{
			String name = (String) en.nextElement();
			String value = (String) params.get(name);
			length += name.length() + 1 + value.length() + 1;
		}

		length += 1;

		/*
		 * Send the startup message.
		 */
		stream.SendInteger(length, 4);
		stream.SendInteger(protocolMajor, 2);
		stream.SendInteger(protocolMinor, 2);

		en = params.keys();
		while (en.hasMoreElements())
		{
			String name = (String) en.nextElement();
			String value = (String) params.get(name);
			stream.Send(name.getBytes());
			stream.SendChar(0);
			stream.Send(value.getBytes());
			stream.SendChar(0);
		}

		stream.SendChar(0);
	}

	public void v2WriteTo(PGStream stream) throws IOException
	{
		String user = (String)params.get("user");
		String database = (String)params.get("database");

		stream.SendInteger(4 + 4 + SM_DATABASE + SM_USER + SM_OPTIONS + SM_UNUSED + SM_TTY, 4);
		stream.SendInteger(protocolMajor, 2);
		stream.SendInteger(protocolMinor, 2);
		stream.Send(database.getBytes(), SM_DATABASE);

		// This last send includes the unused fields
		stream.Send(user.getBytes(), SM_USER + SM_OPTIONS + SM_UNUSED + SM_TTY);
	}
}

