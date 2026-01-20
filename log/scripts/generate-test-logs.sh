#!/bin/bash

# 生成测试日志脚本
# 用于快速生成测试日志数据

LOG_DIR="/var/log/app"
LOG_FILE="${LOG_DIR}/application.log"

# 创建日志目录
mkdir -p ${LOG_DIR}

# 日志级别
LEVELS=("INFO" "DEBUG" "WARN" "ERROR")

# 生成日志
for i in {1..100}; do
    LEVEL=${LEVELS[$RANDOM % ${#LEVELS[@]}]}
    TIMESTAMP=$(date '+%Y-%m-%d %H:%M:%S.%3N')
    MESSAGE="测试日志消息 - 序号: $i, 级别: $LEVEL, 时间: $(date '+%s')"
    
    echo "[$TIMESTAMP] [$LEVEL] org.lix.mycatdemo.TestClass - $MESSAGE" >> ${LOG_FILE}
    
    # 随机延迟
    sleep 0.1
done

echo "已生成 100 条测试日志到 ${LOG_FILE}"

