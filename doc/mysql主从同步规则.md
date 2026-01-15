你想了解MySQL主从同步具体会同步哪些信息，特别是建库、建表这类DDL语句是否会被同步，我来为你详细解答。

### MySQL主从同步的核心同步内容
MySQL主从同步的核心是基于**二进制日志（binlog）** 进行的，主库会把所有会改变数据/结构的操作记录到binlog中，从库通过读取并执行这些binlog来保持和主库的一致。具体同步的信息可以分为以下几类：

#### 1. 数据变更操作（DML）
这是最核心的同步内容，所有修改数据的操作都会被同步：
- 增删改操作：`INSERT`、`DELETE`、`UPDATE` 语句及对应的执行结果
- 批量数据操作：`LOAD DATA INFILE`（需特殊配置）
- 事务相关：事务的提交/回滚、保存点（SAVEPOINT）等

#### 2. 结构变更操作（DDL）
你关心的建库、建表语句**会被同步**，具体包括：
- 建库：`CREATE DATABASE` / `ALTER DATABASE` / `DROP DATABASE`
- 建表：`CREATE TABLE` / `ALTER TABLE` / `DROP TABLE` / `TRUNCATE TABLE`
- 索引操作：`CREATE INDEX` / `DROP INDEX`
- 其他结构变更：`RENAME TABLE`、`ALTER VIEW`、`CREATE/DROP PROCEDURE`（存储过程）、`CREATE/DROP FUNCTION`（函数）等

#### 3. 权限相关操作（部分）
- 用户/权限操作：`CREATE USER`、`DROP USER`、`GRANT`、`REVOKE`（需确保binlog格式不是`STATEMENT`，或开启相关配置）
- 密码修改：`SET PASSWORD`

#### 4. 不会同步的内容
有一些操作/信息是**不会**同步的，需要特别注意：
- 主库的配置文件（my.cnf/my.ini）中的参数
- 主库的日志文件（如error log、slow log，仅binlog会被同步）
- 临时表（`CREATE TEMPORARY TABLE`）：默认不会同步，除非显式配置
- 非事务性操作的中间状态（如未提交的事务）
- 主库的物理文件（如数据文件、表空间文件）
- `SELECT`、`SHOW` 等只读操作（因为不会写入binlog）
- 主库的连接数、锁信息、进程列表等运行时状态

### 影响同步范围的关键因素
同步内容也会受以下配置影响，你可以根据需求调整：
1. **binlog格式**：
    - `STATEMENT`：同步SQL语句本身（建库/建表语句会完整同步）
    - `ROW`：同步行数据的变更（建库/建表的DDL仍会同步，因为DDL只能以语句形式记录）
    - `MIXED`：混合模式，自动选择上述两种方式
2. **binlog过滤规则**：
    - 主库可通过 `binlog-do-db`/`binlog-ignore-db` 指定只同步/忽略某个库
    - 从库可通过 `replicate-do-db`/`replicate-ignore-db` 过滤同步的库/表
3. **同步模式**：
    - 异步复制：默认模式，主库写入binlog后立即返回，不等待从库确认
    - 半同步复制：主库需等待至少一个从库接收并确认binlog后才返回，可靠性更高

### 验证建库/建表同步的简单示例
#### 1. 主库操作（创建库和表）
```sql
-- 主库执行
CREATE DATABASE test_sync;  -- 建库语句
USE test_sync;
CREATE TABLE user (         -- 建表语句
  id INT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(50) NOT NULL
);
INSERT INTO user (name) VALUES ('test');  -- 插入数据
```

#### 2. 从库验证
```sql
-- 从库执行
SHOW DATABASES;  -- 能看到test_sync库
USE test_sync;
SHOW TABLES;     -- 能看到user表
SELECT * FROM user;  -- 能看到name='test'的行
```

### 总结
1. MySQL主从同步核心是同步binlog中的内容，**建库（CREATE DATABASE）、建表（CREATE TABLE）等DDL语句会完整同步**，和DML（增删改）一样是同步的核心内容。
2. 同步范围可通过binlog过滤规则（如binlog-do-db）精准控制，比如只同步指定库，或忽略某个库的所有操作。
3. 临时表、只读操作、主库配置参数等不会被同步，需手动在从库配置/执行。