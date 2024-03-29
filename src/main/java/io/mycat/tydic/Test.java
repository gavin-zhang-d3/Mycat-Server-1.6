package io.mycat.tydic;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Random;

import com.google.common.collect.Lists;

import io.mycat.tydic.frontend.JdbcStatement;

public class Test {

	static {
		// 加载可能的驱动
		List<String> drivers = Lists.newArrayList("io.mycat.tydic.driver.JDBCDriver");

		for (String driver : drivers) {
			try {
				Class.forName(driver);
			} catch (ClassNotFoundException ignored) {
			}
		}
	}

	public void excuteInsert() {
		// public static void main(String[] args) {

		try {
			Random r = new Random();
			int id = r.nextInt(1000000);
			Connection conn = DriverManager.getConnection("jdbc:default:connection");
			JdbcStatement stmt = (JdbcStatement) conn.createStatement();
			/*stmt.executeQuery1(
					"insert into tim_message(id,from_id,platform,to_id,to_groupId,type,content,created_time) values ("
							+ id + ",'tydic1',1,'tydic2','tydicgroup',2,'lalallallalala',now())");*/

			stmt.executeQuery1("select * from tim_message order by id");
		} catch (SQLException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
}
