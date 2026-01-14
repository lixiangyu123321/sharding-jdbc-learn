你想了解 JDBC 原生方式建立连接并发送 SQL 命令的完整流程，这是理解 Java 操作数据库底层原理的核心——JDBC 作为 Java 访问数据库的标准接口，其原生流程可拆解为**加载驱动→建立连接→创建执行器→执行SQL→处理结果→释放资源** 6 个核心步骤，我会结合完整代码示例和关键说明，帮你清晰掌握每一步的作用和注意事项。

### 一、JDBC 原生操作核心流程（分步拆解）
#### 1. 核心前置条件
- 引入对应数据库的 JDBC 驱动依赖（如 MySQL 驱动）；
- 准备数据库连接参数：URL、用户名、密码。

#### 2. 完整流程代码示例（以 MySQL 为例）
```java
import java.sql.*;

/**
 * JDBC原生操作数据库完整流程
 */
public class JdbcNativeDemo {
    // 数据库连接参数（需根据实际环境修改）
    private static final String DB_URL = "jdbc:mysql://localhost:3306/test_db?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai";
    private static final String DB_USER = "root";
    private static final String DB_PASSWORD = "123456";

    public static void main(String[] args) {
        // 声明JDBC核心对象（放在try外部，便于finally释放）
        Connection conn = null;
        Statement stmt = null;
        ResultSet rs = null;

        try {
            // 步骤1：加载JDBC驱动（MySQL 8.0+ 可省略，自动加载）
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
                if (rs != null) rs.close();
                // 注意：实际代码中需释放所有Statement/PreparedStatement对象
                // if (pstmt != null) pstmt.close();
                // if (updatePstmt != null) updatePstmt.close();
                if (conn != null) conn.close();
            } catch (SQLException e) {
                System.err.println("资源释放失败：" + e.getMessage());
            }
        }
    }
}
```

#### 3. 每一步核心说明
| 步骤 | 核心操作 | 关键说明 |
|------|----------|----------|
| **1. 加载驱动** | `Class.forName("com.mysql.cj.jdbc.Driver")` | - MySQL 8.0+ 驱动类名是 `com.mysql.cj.jdbc.Driver`（5.x 是 `com.mysql.jdbc.Driver`）；<br>- JDBC 4.0+（Java 6+）可省略此步骤，驱动会通过 `META-INF/services/java.sql.Driver` 自动加载；<br>- 作用：将驱动类加载到JVM，注册到 `DriverManager`。 |
| **2. 建立连接** | `DriverManager.getConnection(URL, 用户名, 密码)` | - 核心是获取 `Connection` 对象（代表数据库连接）；<br>- URL格式（MySQL）：`jdbc:mysql://主机:端口/数据库名?参数1=值1&参数2=值2`；<br>- 关键参数：`serverTimezone`（时区）、`useSSL`（是否SSL连接）、`characterEncoding`（编码）；<br>- 注意：`Connection` 是重量级对象，频繁创建销毁性能差，实际开发用连接池（如Druid、HikariCP）。 |
| **3. 创建执行器** | 可选两种执行器：<br>1. `Statement`：简单执行器，有SQL注入风险；<br>2. `PreparedStatement`：预编译执行器，防SQL注入，推荐使用 | - `Statement`：`conn.createStatement()`，适用于静态SQL（无参数）；<br>- `PreparedStatement`：`conn.prepareStatement(sql)`，适用于带参数的SQL，SQL预编译后可重复执行，性能更高；<br>- 核心区别：`PreparedStatement` 用 `?` 占位符传参，避免拼接SQL导致注入。 |
| **4. 执行SQL** | 分三类执行方法：<br>1. `executeQuery()`：执行SELECT，返回 `ResultSet`；<br>2. `executeUpdate()`：执行INSERT/UPDATE/DELETE，返回受影响行数；<br>3. `execute()`：通用执行方法，可执行任意SQL，返回boolean（是否有结果集） | - `executeQuery()`：仅用于查询，必须传入SELECT语句；<br>- `executeUpdate()`：用于DML操作，返回int类型的受影响行数；<br>- `execute()`：极少用，适用于不确定SQL类型的场景（如动态SQL）。 |
| **5. 处理结果** | 遍历 `ResultSet`（查询结果）或处理受影响行数（更新操作） | - `ResultSet`：代表查询结果集，通过 `next()` 遍历行，`getXxx(列名/索引)` 获取列值；<br>- 列索引从1开始（而非0），推荐用列名（如 `rs.getString("username")`），避免列顺序变化导致错误；<br>- 注意：`ResultSet` 依赖 `Statement` 和 `Connection`，关闭后无法使用。 |
| **6. 释放资源** | 逆序关闭：`ResultSet` → `Statement` → `Connection` | - 所有JDBC对象（`Connection`/`Statement`/`ResultSet`）都实现了 `AutoCloseable`，Java 7+ 可使用 `try-with-resources` 自动关闭；<br>- 必须在 `finally` 中释放，避免资源泄漏；<br>- 示例（try-with-resources）：<br>```java<br>try (Connection conn = DriverManager.getConnection(...);<br>     PreparedStatement pstmt = conn.prepareStatement(sql)) {<br>    // 执行SQL<br>} // 自动释放资源<br>``` |

### 二、关键注意事项（避坑点）
1. **SQL注入风险**：
    - 禁止使用 `Statement` 拼接参数（如 `String sql = "SELECT * FROM t_user WHERE username = '" + username + "'"`）；
    - 必须用 `PreparedStatement` 的 `setXxx()` 方法传参（如 `pstmt.setString(1, username)`）。

2. **资源释放**：
    - `Connection` 是最宝贵的资源，必须确保关闭；
    - `try-with-resources` 是最优写法，无需手动在 `finally` 关闭。

3. **性能优化**：
    - 原生 `Connection` 频繁创建销毁性能差，实际项目中必须使用连接池（如 HikariCP）；
    - `PreparedStatement` 预编译后可重复执行，适合批量操作。

4. **异常处理**：
    - `SQLException` 包含详细的错误信息（错误码、SQL状态、原因），可通过 `getErrorCode()`/`getSQLState()` 定位问题；
    - 生产环境避免直接打印异常栈到前端，需封装为统一提示。

### 三、简化写法（Java 7+ try-with-resources）
```java
public class JdbcSimplifiedDemo {
    public static void main(String[] args) {
        String sql = "SELECT id, username FROM t_user WHERE age > ?";
        // try-with-resources 自动释放资源
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, 18);
            try (ResultSet rs = pstmt.executeQuery()) { // ResultSet 也可放入try-with-resources
                while (rs.next()) {
                    System.out.println("ID：" + rs.getLong("id") + "，用户名：" + rs.getString("username"));
                }
            }
        } catch (SQLException e) {
            System.err.println("数据库操作异常：" + e.getMessage());
        }
    }
}
```

### 总结
1. JDBC 原生流程核心是 **6步**：加载驱动→建立连接→创建执行器→执行SQL→处理结果→释放资源；
2. 核心对象优先级：`PreparedStatement`（替代 `Statement`）、`try-with-resources`（替代手动关闭资源）；
3. 实际开发中，原生 JDBC 仅用于理解原理，生产环境必用**连接池** + ORM框架（如MyBatis）简化操作；
4. 关键避坑点：防SQL注入、确保资源释放、处理时区/编码等连接参数。

简单记：JDBC 原生操作的核心是“获取连接→执行SQL→释放资源”，而 `PreparedStatement` 和连接池是优化性能和安全性的关键。