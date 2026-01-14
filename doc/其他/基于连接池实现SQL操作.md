你想了解**基于 `DataSource` 实现 JDBC 操作的流程**（区别于直接用 `DriverManager`），这是实际项目中更常用的方式（连接池、框架底层均基于 `DataSource`）——`DataSource` 是 JDBC 规范中定义的“数据源接口”，核心作用是封装连接参数、管理数据库连接（可实现连接池），替代 `DriverManager` 这种“每次创建新连接”的低效方式。

### 一、核心区别：`DataSource` vs `DriverManager`
| 特性                | `DriverManager`（原生）       | `DataSource`（推荐）                          |
|---------------------|-------------------------------|-----------------------------------------------|
| 连接创建            | 每次调用 `getConnection()` 新建连接（重量级、性能差） | 可复用连接（连接池实现），仅创建一次数据源，重复获取连接 |
| 封装性              | 连接参数（URL/用户名/密码）散落在代码中 | 连接参数封装在 `DataSource` 实例中，代码更整洁 |
| 扩展性              | 无（仅能创建基础连接）| 支持连接池（Druid/HikariCP）、分布式事务等扩展 |
| 实际使用场景        | 学习/测试场景                 | 生产环境、框架底层（MyBatis/Spring JDBC）      |

### 二、基于 `DataSource` 的 JDBC 操作流程
#### 1. 核心思路
1. **创建 `DataSource` 实例**（封装连接参数，推荐用连接池实现类）；
2. **从 `DataSource` 获取 `Connection`**（连接池会复用连接，而非新建）；
3. 后续流程（创建 `PreparedStatement`、执行 SQL、处理结果、释放资源）与原生 JDBC 一致；
4. 核心优势：`DataSource` 可无缝对接连接池，无需修改业务代码。

#### 2. 完整代码示例（分两种场景）
##### 场景1：基础实现（无连接池，仅演示 `DataSource` 用法）
使用 MySQL 提供的 `MysqlDataSource`（纯基础实现，无连接池，仅用于理解原理）：
```java
import com.mysql.cj.jdbc.MysqlDataSource;
import java.sql.*;

public class DataSourceDemo {
    public static void main(String[] args) {
        // 1. 创建并配置 DataSource 实例（封装连接参数）
        MysqlDataSource dataSource = new MysqlDataSource();
        dataSource.setURL("jdbc:mysql://localhost:3306/test_db?serverTimezone=Asia/Shanghai");
        dataSource.setUser("root");
        dataSource.setPassword("123456");

        // 2. 从 DataSource 获取连接（核心：替代 DriverManager.getConnection()）
        try (Connection conn = dataSource.getConnection(); // try-with-resources 自动释放
             // 3. 创建 PreparedStatement
             PreparedStatement pstmt = conn.prepareStatement("SELECT id, username FROM t_user WHERE age > ?")) {

            // 4. 设置参数 + 执行 SQL
            pstmt.setInt(1, 18);
            try (ResultSet rs = pstmt.executeQuery()) {
                // 5. 处理结果
                while (rs.next()) {
                    System.out.printf("ID：%d，用户名：%s%n", rs.getLong("id"), rs.getString("username"));
                }
            }

        } catch (SQLException e) {
            System.err.println("数据库操作异常：" + e.getMessage());
        }
    }
}
```

##### 场景2：生产环境（带连接池，HikariCP 示例）
实际项目中必用连接池，以 Spring 标配的 HikariCP 为例（性能最优）：
```java
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.*;

public class HikariCPDemo {
    public static void main(String[] args) {
        // 1. 配置连接池参数
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://localhost:3306/test_db?serverTimezone=Asia/Shanghai");
        config.setUsername("root");
        config.setPassword("123456");
        // 连接池核心参数（按需配置）
        config.setMaximumPoolSize(10); // 最大连接数
        config.setMinimumIdle(2); // 最小空闲连接数
        config.setConnectionTimeout(3000); // 连接超时时间（ms）
        config.setIdleTimeout(60000); // 空闲连接超时时间（ms）

        // 2. 创建 HikariDataSource（连接池实现的 DataSource）
        try (HikariDataSource dataSource = new HikariDataSource(config);
             // 3. 从连接池获取连接（复用连接，而非新建）
             Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement("UPDATE t_user SET age = ? WHERE id = ?")) {

            // 4. 执行更新操作
            pstmt.setInt(1, 21);
            pstmt.setLong(2, 1L);
            int affectedRows = pstmt.executeUpdate();
            System.out.println("受影响行数：" + affectedRows);

        } catch (SQLException e) {
            System.err.println("数据库操作异常：" + e.getMessage());
        }
    }
}
```

### 三、核心步骤详解
#### 1. 创建 `DataSource` 实例（核心）
`DataSource` 是接口，实际使用需用具体实现类：
- **基础实现**：`MysqlDataSource`（MySQL）、`OracleDataSource`（Oracle）——无连接池，仅封装参数；
- **连接池实现**：`HikariDataSource`（HikariCP）、`DruidDataSource`（阿里Druid）、`BasicDataSource`（Commons DBCP）——生产环境首选。

#### 2. 获取 `Connection`
```java
Connection conn = dataSource.getConnection();
```
- 基础 `DataSource`：每次调用新建连接（和 `DriverManager` 一样）；
- 连接池 `DataSource`：从连接池复用空闲连接，无空闲则等待（超时则抛异常），释放连接时仅归还到池，不真正关闭。

#### 3. 后续流程（与原生 JDBC 一致）
创建 `PreparedStatement`、执行 SQL、处理 `ResultSet`、释放资源的逻辑完全不变，核心差异仅在“连接获取方式”。

### 四、Spring 中使用 `DataSource`（实战场景）
Spring 会自动管理 `DataSource` 和连接池，只需配置即可：
#### 1. 配置 `application.yml`
```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/test_db?serverTimezone=Asia/Shanghai
    username: root
    password: 123456
    driver-class-name: com.mysql.cj.jdbc.Driver
    # HikariCP 连接池参数（Spring Boot 2.x 默认使用 HikariCP）
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2
      connection-timeout: 3000
```

#### 2. 注入 `DataSource` 使用
```java
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class UserDao {
    // Spring 提供的 JdbcTemplate 底层基于 DataSource
    @Autowired
    private JdbcTemplate jdbcTemplate;

    public void queryUser() {
        jdbcTemplate.query("SELECT id, username FROM t_user WHERE age > ?",
                new Object[]{18},
                (rs, rowNum) -> {
                    System.out.printf("ID：%d，用户名：%s%n", rs.getLong("id"), rs.getString("username"));
                    return null;
                });
    }
}
```

### 五、关键注意事项
1. **连接池是核心**：
    - 纯 `DataSource`（如 `MysqlDataSource`）无性能优势，必须用连接池实现（HikariCP/Druid）；
    - 连接池参数需合理配置（最大连接数、超时时间），避免连接耗尽或空闲连接过多。

2. **资源释放**：
    - `Connection` 仍需关闭（`try-with-resources`），连接池实现的 `close()` 仅归还连接到池，而非真正关闭；
    - `Statement`/`ResultSet` 仍需正常关闭，避免资源泄漏。

3. **扩展性**：
    - `DataSource` 支持分布式事务（如 `XADataSource`）、读写分离（如 ShardingSphere-JDBC 封装的 `DataSource`）；
    - 所有 ORM 框架（MyBatis、JPA）底层均基于 `DataSource` 获取连接。

### 总结
1. **核心流程**：基于 `DataSource` 的 JDBC 操作 = **创建 DataSource（封装参数/连接池）→ 获取 Connection → 执行 SQL → 释放资源**；
2. **核心优势**：`DataSource` 封装连接参数、支持连接池，是生产环境的标准方式，替代低效的 `DriverManager`；
3. **实战要点**：生产环境必用连接池（HikariCP/Druid），Spring 中可通过配置自动管理 `DataSource`；
4. **兼容性**：获取 `Connection` 后的所有操作与原生 JDBC 完全一致，学习成本低。

简单记：`DataSource` 是 `DriverManager` 的“升级版”，核心解决“连接复用”和“参数封装”问题，是实际项目中 JDBC 操作的基础。