package org.lix.mycatdemo.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import javax.sql.DataSource;
import java.sql.*;

@Controller
public class TestController {

    private static final String DB_URL = "jdbc:mysql://localhost:3306/test_db?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai";
    private static final String DB_USER = "root";
    private static final String DB_PASSWORD = "123456";

    @Autowired
    private DataSource dataSource;


    @GetMapping
    public void test(){
        Connection conn = null;
        Statement stmt = null;
        ResultSet rs = null;

        try {
            // 步骤1：加载JDBC驱动（MySQL 8.0+ 可省略，自动加载）
            // XXX 其实这里就是进行一下判断
            Class.forName("com.mysql.cj.jdbc.Driver");

            // 步骤2：建立数据库连接（核心，获取Connection对象）
            conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
            System.out.println("数据库连接成功！");

            // 步骤3：创建SQL执行器（Statement/PreparedStatement）
            // 推荐使用PreparedStatement（防SQL注入、预编译）
            String sql = "SELECT id, username, age FROM t_user WHERE age > ?";
            PreparedStatement pstmt = conn.prepareStatement(sql);
            // 设置SQL参数（索引从1开始）
            pstmt.setInt(1, 18);

            // 步骤4：执行SQL命令（区分查询/更新操作）
            // 执行查询（SELECT）：返回ResultSet结果集
            rs = pstmt.executeQuery();

            // 步骤5：处理执行结果（遍历ResultSet）
            while (rs.next()) {
                Long id = rs.getLong("id");
                String username = rs.getString("username");
                int age = rs.getInt("age");
                System.out.printf("用户ID：%d，用户名：%s，年龄：%d%n", id, username, age);
            }

            // 示例：执行更新操作（INSERT/UPDATE/DELETE）
            String updateSql = "UPDATE t_user SET age = ? WHERE id = ?";
            PreparedStatement updatePstmt = conn.prepareStatement(updateSql);
            updatePstmt.setInt(1, 20);
            updatePstmt.setLong(2, 1L);
            // 执行更新：返回受影响的行数
            int affectedRows = updatePstmt.executeUpdate();
            System.out.println("受影响的行数：" + affectedRows);

        } catch (ClassNotFoundException e) {
            // 驱动加载失败
            System.err.println("JDBC驱动加载失败：" + e.getMessage());
        } catch (SQLException e) {
            // 数据库操作异常（连接失败、SQL语法错误、参数错误等）
            System.err.println("数据库操作异常：" + e.getMessage());
            e.printStackTrace();
        } finally {
            // 步骤6：释放资源（逆序释放：ResultSet → Statement → Connection）
            try {
                if (rs != null) {
                    rs.close();
                }
                // 注意：实际代码中需释放所有Statement/PreparedStatement对象
                // if (pstmt != null) pstmt.close();
                // if (updatePstmt != null) updatePstmt.close();
                if (conn != null) {
                    conn.close();
                }
            } catch (SQLException e) {
                System.err.println("资源释放失败：" + e.getMessage());
            }
        }


    }
}
