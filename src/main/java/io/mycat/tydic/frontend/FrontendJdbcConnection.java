/*
 * 
 * author: D3
 * 
 */
package io.mycat.tydic.frontend;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.mycat.MycatServer;
import io.mycat.backend.mysql.MySQLMessage;
import io.mycat.config.ErrorCode;
import io.mycat.config.model.SchemaConfig;
import io.mycat.config.model.SystemConfig;
import io.mycat.net.NIOProcessor;
import io.mycat.net.handler.FrontendPrepareHandler;
import io.mycat.net.handler.FrontendQueryHandler;
import io.mycat.net.mysql.OkPacket;
import io.mycat.server.NonBlockingSession;
import io.mycat.server.ServerConnection;
import io.mycat.server.ServerQueryHandler;
import io.mycat.server.parser.ServerParse;
import io.mycat.server.util.SchemaUtil;
import io.mycat.tydic.driver.JdbcDriverBackwardsCompat;
import io.mycat.util.CompressUtil;
import io.mycat.util.StringUtil;

/**
 * <p>
 * Represents a connection (session) to a database.
 * </p>
 * <p>
 * Thread safety: the connection is thread-safe, because access is synchronized.
 * However, for compatibility with other databases, a connection should only be
 * used in one thread at any time.
 * </p>
 */
public class FrontendJdbcConnection extends ServerConnection implements Connection, JdbcDriverBackwardsCompat {

	protected static final Logger LOGGER = LoggerFactory.getLogger(FrontendJdbcConnection.class);

	// protected FrontendPrivileges privileges;
	protected FrontendQueryHandler queryHandler;
	protected FrontendPrepareHandler prepareHandler;
	// protected LoadDataInfileHandler loadDataInfileHandler;

	public FrontendJdbcConnection() throws IOException {
		super();
		SystemConfig sys = MycatServer.getInstance().getConfig().getSystem();
		this.setCharset("utf8");
		this.setUser("zhanggh");
		this.setSchema("MYCATTESTDB");
		this.queryHandler = new ServerQueryHandler(this);
		// this.prepareHandler = new ServerPrepareHandler(this);
		this.setSession2(new NonBlockingSession(this));
		this.setTxIsolation(sys.getTxIsolation());
		// this.loadDataInfileHandler = new ServerLoadDataInfileHandler(this);
		NIOProcessor processor = (NIOProcessor) MycatServer.getInstance().nextProcessor();
		this.setProcessor(processor);

		processor.getExecutor().execute(new Runnable() {
			@Override
			public void run() {
				try {

					// 处理写事件
					ByteBuffer bf = writeBuffer;
					while (true) {
						if ((bf = writeQueue.poll()) != null) {
							if (bf.limit() == 0) {
								recycle(bf);
								close("quit send");
							}

							bf.flip();
							try {
								while (bf.hasRemaining()) {
									// System.out.println(StringUtil.decode(readFromBuffer(bf), charset));
									System.out.println(new String(readFromBuffer(bf)));
									// MySQLMessage mm = new MySQLMessage(readFromBuffer(bf));
									/*	OkPacket ok = new OkPacket();
										ok.read(readFromBuffer(bf));
										System.out.println(new String(ok.message))*/;

								}
							} catch (Exception e) {
								recycle(bf);
								throw e;
							}

						}
					}

				} catch (Exception e) {
					LOGGER.warn("write err:", e);
					close("write err:" + e);
				}
			}

		});

	}

	public byte[] readFromBuffer(ByteBuffer buffer) {
		byte[] data = new byte[buffer.remaining()];
		buffer.get(data);
		return data;

	}

	@Override
	public final void write(ByteBuffer buffer) {

		// 首先判断是否为压缩协议
		if (isSupportCompress()) {

			// CompressUtil为压缩协议辅助工具类
			ByteBuffer newBuffer = CompressUtil.compressMysqlPacket(buffer, this, compressUnfinishedDataQueue);

			// 将要写的数据先放入写缓存队列
			writeQueue.offer(newBuffer);
		} else {

			// 将要写的数据先放入写缓存队列
			writeQueue.offer(buffer);
		}

	}

	public void query(String sql) {

		if (sql == null || sql.length() == 0) {
			writeErrMessage(ErrorCode.ER_NOT_ALLOWED_COMMAND, "Empty SQL");
			return;
		}

		if (LOGGER.isDebugEnabled()) {
			LOGGER.debug(new StringBuilder().append(this).append(" ").append(sql).toString());
		}

		// remove last ';'
		if (sql.endsWith(";")) {
			sql = sql.substring(0, sql.length() - 1);
		}

		// 记录SQL
		this.setExecuteSql(sql);

		// 执行查询
		if (queryHandler != null) {
			queryHandler.query(sql);

		} else {
			writeErrMessage(ErrorCode.ER_UNKNOWN_COM_ERROR, "Query unsupported!");
		}
	}

	public void execute(String sql, int type) {
		// 连接状态检查
		if (this.isClosed()) {
			LOGGER.warn("ignore execute ,server connection is closed " + this);
			return;
		}
		// 事务状态检查
		if (txInterrupted) {
			// writeErrMessage(ErrorCode.ER_YES, "Transaction error, need to rollback." +
			// txInterrputMsg);
			writeErrMessage(ErrorCode.ER_YES, "Transaction error, need to rollback.");
			return;
		}

		// 检查当前使用的DB
		String db = this.schema;
		boolean isDefault = true;
		if (db == null) {
			db = SchemaUtil.detectDefaultDb(sql, type);
			if (db == null) {
				writeErrMessage(ErrorCode.ERR_BAD_LOGICDB, "No MyCAT Database selected");
				return;
			}
			isDefault = false;
		}

		SchemaConfig schema = MycatServer.getInstance().getConfig().getSchemas().get(db);
		if (schema == null) {
			writeErrMessage(ErrorCode.ERR_BAD_LOGICDB, "Unknown MyCAT Database '" + db + "'");
			return;
		}

		/*
		 * 当已经设置默认schema时，可以通过在sql中指定其它schema的方式执行 相关sql，已经在mysql客户端中验证。
		 * 所以在此处增加关于sql中指定Schema方式的支持。
		 */
		if (isDefault && schema.isCheckSQLSchema() && isNormalSql(type)) {
			SchemaUtil.SchemaInfo schemaInfo = SchemaUtil.parseSchema(sql);
			if (schemaInfo != null && schemaInfo.schema != null && !schemaInfo.schema.equals(db)) {
				SchemaConfig schemaConfig = MycatServer.getInstance().getConfig().getSchemas().get(schemaInfo.schema);
				if (schemaConfig != null)
					schema = schemaConfig;
			}
		}

		routeEndExecuteSQL(sql, type, schema);

	}

	private boolean isNormalSql(int type) {
		return ServerParse.SELECT == type || ServerParse.INSERT == type || ServerParse.UPDATE == type
				|| ServerParse.DELETE == type || ServerParse.DDL == type;
	}

	public void initDB(byte[] data) {

	}

	public void query(byte[] data) {

	}

	public void ping() {
	}

	public void kill(byte[] data) {
	}

	public void stmtPrepare(byte[] data) {
	}

	public void stmtSendLongData(byte[] data) {
	}

	public void stmtReset(byte[] data) {
	}

	public void stmtExecute(byte[] data) {
		if (prepareHandler != null) {
			prepareHandler.execute(data);
		} else {
			writeErrMessage(ErrorCode.ER_UNKNOWN_COM_ERROR, "Prepare unsupported!");
		}
	}

	public void stmtClose(byte[] data) {
	}

	public void heartbeat(byte[] data) {
	}

	public void writeErrMessage(int errno, String msg) {
	}

	@Override
	public <T> T unwrap(Class<T> iface) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public boolean isWrapperFor(Class<?> iface) throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public Statement createStatement() throws SQLException {
		// TODO Auto-generated method stub
		return new JdbcStatement(this);
	}

	@Override
	public PreparedStatement prepareStatement(String sql) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public CallableStatement prepareCall(String sql) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String nativeSQL(String sql) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void setAutoCommit(boolean autoCommit) throws SQLException {
		// TODO Auto-generated method stub

	}

	@Override
	public boolean getAutoCommit() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	public void close(String reason) {
		super.close(reason);
	}

	@Override
	public void close() throws SQLException {
	}

	@Override
	public DatabaseMetaData getMetaData() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void setReadOnly(boolean readOnly) throws SQLException {
		// TODO Auto-generated method stub

	}

	@Override
	public boolean isReadOnly() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public void setCatalog(String catalog) throws SQLException {
		// TODO Auto-generated method stub

	}

	@Override
	public String getCatalog() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void setTransactionIsolation(int level) throws SQLException {
		// TODO Auto-generated method stub

	}

	@Override
	public int getTransactionIsolation() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public SQLWarning getWarnings() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void clearWarnings() throws SQLException {
		// TODO Auto-generated method stub

	}

	@Override
	public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency)
			throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Map<String, Class<?>> getTypeMap() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
		// TODO Auto-generated method stub

	}

	@Override
	public void setHoldability(int holdability) throws SQLException {
		// TODO Auto-generated method stub

	}

	@Override
	public int getHoldability() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public Savepoint setSavepoint() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Savepoint setSavepoint(String name) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void rollback(Savepoint savepoint) throws SQLException {
		// TODO Auto-generated method stub

	}

	@Override
	public void releaseSavepoint(Savepoint savepoint) throws SQLException {
		// TODO Auto-generated method stub

	}

	public Statement createStatement1() throws SQLException {

		return null;
	}

	@Override
	public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability)
			throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency,
			int resultSetHoldability) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency,
			int resultSetHoldability) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Clob createClob() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Blob createBlob() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public NClob createNClob() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public SQLXML createSQLXML() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public boolean isValid(int timeout) throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public void setClientInfo(String name, String value) throws SQLClientInfoException {
		// TODO Auto-generated method stub

	}

	@Override
	public void setClientInfo(Properties properties) throws SQLClientInfoException {
		// TODO Auto-generated method stub

	}

	@Override
	public String getClientInfo(String name) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Properties getClientInfo() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void abort(Executor executor) throws SQLException {
		// TODO Auto-generated method stub

	}

	@Override
	public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
		// TODO Auto-generated method stub

	}

	@Override
	public int getNetworkTimeout() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	/*
	 * @Override public void commit() { session.commit(); }
	 * 
	 * @Override public void rollback() { session.rollback(); }
	 */

}
