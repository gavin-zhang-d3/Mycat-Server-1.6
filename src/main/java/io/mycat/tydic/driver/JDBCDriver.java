package io.mycat.tydic.driver;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Properties;
import java.util.logging.Logger;

import io.mycat.tydic.frontend.FrontendJdbcConnection;

public class JDBCDriver implements Driver {

	private static final JDBCDriver INSTANCE = new JDBCDriver();
	private static final String DEFAULT_URL = "jdbc:default:connection";
	private static final ThreadLocal<Connection> DEFAULT_CONNECTION = new ThreadLocal<Connection>();

	private static volatile boolean registered;

	static {
		load();
	}

	/**
	 * Open a database connection. This method should not be called by an
	 * application. Instead, the method DriverManager.getConnection should be used.
	 *
	 * @param url
	 *            the database URL
	 * @param info
	 *            the connection properties
	 * @return the new connection or null if the URL is not supported
	 */
	public Connection connect(String url, Properties info)  {
		FrontendJdbcConnection conn = null;
		try {
			if (info == null) {
				info = new Properties();
			}
			conn= new FrontendJdbcConnection();
		} catch (Exception e) {
			e.printStackTrace();
		}
		
		return conn;
	}

	/**
	 * Check if the driver understands this URL. This method should not be called by
	 * an application.
	 *
	 * @param url
	 *            the database URL
	 * @return if the driver understands the URL
	 */
	@Override
	public boolean acceptsURL(String url) {
		if (url != null) {
			if (url.startsWith(Constants.START_URL)) {
				return true;
			} else if (url.equals(DEFAULT_URL)) {
				return DEFAULT_CONNECTION.get() != null;
			}
		}
		return false;
	}

	/**
	 * Get the list of supported properties. This method should not be called by an
	 * application.
	 *
	 * @param url
	 *            the database URL
	 * @param info
	 *            the connection properties
	 * @return a zero length array
	 */
	@Override
	public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
		return new DriverPropertyInfo[0];
	}

	/**
	 * Check if this driver is compliant to the JDBC specification. This method
	 * should not be called by an application.
	 *
	 * @return true
	 */
	@Override
	public boolean jdbcCompliant() {
		return true;
	}

	/**
	 * [Not supported]
	 */
	@Override
	public Logger getParentLogger() {
		return null;
	}

	/**
	 * INTERNAL
	 */
	public static synchronized JDBCDriver load() {
		try {
			if (!registered) {
				registered = true;
				DriverManager.registerDriver(INSTANCE);
			}
		} catch (SQLException e) {
			// DbException.traceThrowable(e);
			System.out.println(e);
		}
		return INSTANCE;
	}

	/**
	 * INTERNAL
	 */
	public static synchronized void unload() {
		try {
			if (registered) {
				registered = false;
				DriverManager.deregisterDriver(INSTANCE);
			}
		} catch (SQLException e) {
			// DbException.traceThrowable(e);
			System.out.println(e);
		}
	}

	/**
	 * INTERNAL Sets, on a per-thread basis, the default-connection for user-defined
	 * functions.
	 */
	public static void setDefaultConnection(Connection c) {
		if (c == null) {
			DEFAULT_CONNECTION.remove();
		} else {
			DEFAULT_CONNECTION.set(c);
		}
	}

	/**
	 * INTERNAL
	 */
	public static void setThreadContextClassLoader(Thread thread) {
		// Apache Tomcat: use the classloader of the driver to avoid the
		// following log message:
		// org.apache.catalina.loader.WebappClassLoader clearReferencesThreads
		// SEVERE: The web application appears to have started a thread named
		// ... but has failed to stop it.
		// This is very likely to create a memory leak.
		try {
			thread.setContextClassLoader(JDBCDriver.class.getClassLoader());
		} catch (Throwable t) {
			// ignore
		}
	}

	@Override
	public int getMajorVersion() {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getMinorVersion() {
		// TODO Auto-generated method stub
		return 0;
	}

}
