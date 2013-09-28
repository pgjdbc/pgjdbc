package org.postgresql.jdbc.ssl.test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

public class Utils {
	private Utils() {
	}
	
	public static String getClasspathFile(String path) throws IOException {
		InputStream in = Thread.currentThread().getContextClassLoader()
				.getResourceAsStream(path);
		StringBuilder sb = new StringBuilder();
		String line = null;
		BufferedReader br = new BufferedReader(new InputStreamReader(in));
		try {
			while ((line = br.readLine()) != null) {
				sb.append(line);
				sb.append("\n");
			}
			return sb.toString();
		} finally {
			try {
				br.close();
			} catch (Exception e) {
			}
		}
	}

}
