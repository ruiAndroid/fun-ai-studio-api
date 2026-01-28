# 89服务器 MongoDB 部署指南（传统安装方式）

本文档提供89服务器上使用传统方式（非Docker）部署MongoDB的完整步骤。

---

## 1. 服务器信息

- **服务器编号**: 89
- **用途**: 独立MongoDB数据库服务器，专门为用户部署的应用提供数据存储
- **MongoDB版本**: 7.0.14 (Community Edition)
- **端口**: 27017
- **数据目录**: `/data/funai/mongo/data`
- **日志目录**: `/data/funai/mongo/log`

---

## 2. 安装步骤

### 2.1 检查系统版本（重要）

在安装前，先检查系统版本以确定正确的仓库URL：

```bash
# 检查系统版本
cat /etc/redhat-release
cat /etc/os-release
```

**系统版本对应关系：**
- CentOS/RHEL 7 → 使用 `redhat/7`
- CentOS/RHEL 8 → 使用 `redhat/8`
- CentOS/RHEL 9 / Rocky Linux 9 → 使用 `redhat/9`
- **Alibaba Cloud Linux 3** → 使用 `redhat/8` (兼容RHEL 8)
- **Alibaba Cloud Linux 2** → 使用 `redhat/7` (兼容RHEL 7)

### 2.2 CentOS/RHEL/Alibaba Cloud Linux 系统安装

**重要：** 根据上面检查的系统版本，将下面命令中的 `8` 替换为对应的版本号（7/8/9）。

```bash
# 1. 添加MongoDB YUM仓库
# 注意：将下面的 '8' 替换为你的系统对应版本号
# Alibaba Cloud Linux 3 使用 8
# Alibaba Cloud Linux 2 使用 7
cat > /etc/yum.repos.d/mongodb-org-7.0.repo <<EOF
[mongodb-org-7.0]
name=MongoDB Repository
baseurl=https://repo.mongodb.org/yum/redhat/8/mongodb-org/7.0/x86_64/
gpgcheck=1
enabled=1
gpgkey=https://www.mongodb.org/static/pgp/server-7.0.asc
EOF

# 2. 清除缓存（如果之前配置过错误的仓库）
yum clean all

# 3. 安装MongoDB 7.0.14
# 注意：MongoDB 7.0不再有mongodb-org-shell，使用mongodb-mongosh（会自动安装）
yum install -y mongodb-org-7.0.14 \
  mongodb-org-database-7.0.14 \
  mongodb-org-server-7.0.14 \
  mongodb-org-mongos-7.0.14 \
  mongodb-org-tools-7.0.14

# 4. 锁定版本（防止自动升级）
sed -i '/\[mongodb-org-7.0\]/a exclude=mongodb-org,mongodb-org-database,mongodb-org-server,mongodb-mongosh,mongodb-org-mongos,mongodb-org-tools' /etc/yum.conf

# 5. 验证安装版本
mongod --version
mongosh --version
```

**常见问题：**

如果遇到 `404` 或 `Cannot download repomd.xml` 错误，说明版本号不对，请：
1. 检查 `/etc/os-release` 中的 `PLATFORM_ID` 或 `VERSION_ID`
2. 修改仓库配置中的版本号（7/8/9）
3. 执行 `yum clean all` 后重试

如果遇到 `Curl error (28): Timeout was reached` / 下载 RPM 超时：

```bash
# 1) 清理缓存（含下载失败的包）
yum clean all
yum clean packages

# 2) 安装时临时加大超时与重试（推荐）
yum install -y \
  --setopt=timeout=600 \
  --setopt=retries=20 \
  mongodb-org-7.0.14 \
  mongodb-org-database-7.0.14 \
  mongodb-org-server-7.0.14 \
  mongodb-org-mongos-7.0.14 \
  mongodb-org-tools-7.0.14
```

如果你把仓库切到阿里云镜像后遇到 `checksum doesn't match`：

- 这通常是镜像站**仓库元数据不同步/缓存污染**导致（`repomd.xml` 指向的新校验值，但镜像返回了旧的 `primary.xml.gz/filelists.xml.gz`）。
- 解决建议：**回退到官方仓库**（`repo.mongodb.org`）并使用上面的 `timeout/retries` 安装；比在镜像站上“碰运气”更可控。

兜底方案（绕开YUM仓库元数据）：**使用官方 tar 包安装**（适合网络不稳定或需要离线复制安装）

```bash
# 1) 下载 tar 包（RHEL 8 兼容包）
cd /tmp
curl -fL -o mongodb.tgz https://fastdl.mongodb.org/linux/mongodb-linux-x86_64-rhel80-7.0.14.tgz

# 2) 解压并安装到 /opt
mkdir -p /opt/mongodb
tar -xzf mongodb.tgz -C /opt/mongodb --strip-components=1

# 3) 配置 PATH（示例：写入profile）
echo 'export PATH=/opt/mongodb/bin:$PATH' > /etc/profile.d/mongodb.sh
source /etc/profile.d/mongodb.sh

# 4) 后续仍按本文档第3章配置 mongod.conf 与 systemd 服务
```

### 2.3 Ubuntu/Debian 系统安装

```bash
# 1. 导入公钥
curl -fsSL https://www.mongodb.org/static/pgp/server-7.0.asc | sudo gpg --dearmor -o /usr/share/keyrings/mongodb-server-7.0.gpg

# 2. 添加MongoDB APT源（Ubuntu 20.04示例）
echo "deb [ arch=amd64,arm64 signed-by=/usr/share/keyrings/mongodb-server-7.0.gpg ] https://repo.mongodb.org/apt/ubuntu focal/mongodb-org/7.0 multiverse" | sudo tee /etc/apt/sources.list.d/mongodb-org-7.0.list

# 对于Ubuntu 22.04，使用：
# echo "deb [ arch=amd64,arm64 signed-by=/usr/share/keyrings/mongodb-server-7.0.gpg ] https://repo.mongodb.org/apt/ubuntu jammy/mongodb-org/7.0 multiverse" | sudo tee /etc/apt/sources.list.d/mongodb-org-7.0.list

# 3. 更新软件包列表
sudo apt-get update

# 4. 安装MongoDB 7.0.14
sudo apt-get install -y mongodb-org=7.0.14 \
  mongodb-org-database=7.0.14 \
  mongodb-org-server=7.0.14 \
  mongodb-org-mongos=7.0.14 \
  mongodb-org-tools=7.0.14

# 5. 锁定版本（防止自动升级）
echo "mongodb-org hold" | sudo dpkg --set-selections
echo "mongodb-org-database hold" | sudo dpkg --set-selections
echo "mongodb-org-server hold" | sudo dpkg --set-selections
echo "mongodb-mongosh hold" | sudo dpkg --set-selections
echo "mongodb-org-mongos hold" | sudo dpkg --set-selections
echo "mongodb-org-tools hold" | sudo dpkg --set-selections

# 6. 查看安装版本
mongod --version
mongosh --version
```

---

---

## 3. 配置MongoDB

### 3.1 创建数据和日志目录

```bash
# 创建数据目录
sudo mkdir -p /data/funai/mongo/data

# 创建日志目录
sudo mkdir -p /data/funai/mongo/log

# 创建备份目录
sudo mkdir -p /data/funai/mongo/backups

# 设置权限（mongod默认以mongod用户运行）
sudo chown -R mongod:mongod /data/funai/mongo

# 设置权限模式
sudo chmod 755 /data/funai/mongo
sudo chmod 755 /data/funai/mongo/data
sudo chmod 755 /data/funai/mongo/log
sudo chmod 755 /data/funai/mongo/backups
```

### 3.2 修改MongoDB配置文件

编辑配置文件 `/etc/mongod.conf`:

```bash
sudo vim /etc/mongod.conf
```

修改以下配置项：

```yaml
# 数据存储
storage:
  dbPath: /data/funai/mongo/data
  journal:
    enabled: true
  wiredTiger:
    engineConfig:
      cacheSizeGB: 2  # 根据服务器内存调整，建议为总内存的50%

# 日志
systemLog:
  destination: file
  logAppend: true
  path: /data/funai/mongo/log/mongod.log
  logRotate: reopen

# 网络配置
net:
  port: 27017
  bindIp: 0.0.0.0  # 允许所有内网访问（后续通过防火墙限制）

# 进程管理
processManagement:
  timeZoneInfo: /usr/share/zoneinfo
  fork: false  # systemd管理时不需要fork

# 安全配置（初始先不启用，创建用户后再启用）
#security:
#  authorization: enabled
```

---

## 4. 启动MongoDB服务

```bash
# 启动MongoDB
sudo systemctl start mongod

# 查看状态
sudo systemctl status mongod

# 设置开机自启动
sudo systemctl enable mongod

# 查看日志
sudo tail -f /data/funai/mongo/log/mongod.log
```

---

## 5. 创建管理员用户和应用用户

### 5.1 连接到MongoDB

```bash
mongosh
```

### 5.2 创建管理员用户

```javascript
// 切换到admin数据库
use admin

// 创建管理员
db.createUser({
  user: "admin",
  pwd: "your_strong_admin_password",  // 请使用强密码！
  roles: [
    { role: "userAdminAnyDatabase", db: "admin" },
    { role: "readWriteAnyDatabase", db: "admin" },
    { role: "dbAdminAnyDatabase", db: "admin" }
  ]
})

// 退出
exit
```

### 5.3 启用认证

编辑 `/etc/mongod.conf`，取消注释安全配置：

```yaml
security:
  authorization: enabled
```

重启MongoDB：

```bash
sudo systemctl restart mongod
```

### 5.4 创建应用专用用户

```bash
# 使用管理员登录
mongosh -u admin -p your_strong_admin_password --authenticationDatabase admin
```

```javascript
// 创建应用用户（拥有所有db_前缀数据库的读写权限）
use admin

db.createUser({
  user: "funai_app",
  pwd: "funai_app_password_2026",  // 请使用强密码！
  roles: [
    { role: "readWrite", db: "admin" }  // 先给admin权限，后续可按需收窄
  ]
})

// 或者创建一个超级应用用户
db.createUser({
  user: "funai_app",
  pwd: "funai_app_password_2026",
  roles: [
    { role: "readWriteAnyDatabase", db: "admin" },
    { role: "dbAdmin", db: "admin" }
  ]
})

exit
```

---

## 6. 测试连接

### 6.1 本机测试

```bash
# 使用管理员测试
mongosh -u admin -p your_strong_admin_password --authenticationDatabase admin

# 使用应用用户测试
mongosh -u funai_app -p funai_app_password_2026 --authenticationDatabase admin
```

### 6.2 从Runtime节点(102)测试

在102服务器上安装mongosh客户端：

```bash
# CentOS/RHEL
sudo yum install -y mongodb-mongosh

# Ubuntu/Debian
sudo apt-get install -y mongodb-mongosh

# 测试连接（替换<89-server-ip>为89的内网IP）
mongosh "mongodb://funai_app:funai_app_password_2026@<89-server-ip>:27017/admin"
```

测试创建数据库：

```javascript
// 测试创建一个应用数据库
use db_test_app

db.test_collection.insertOne({ message: "Hello from Runtime 102", timestamp: new Date() })

db.test_collection.find()

// 删除测试数据
db.dropDatabase()

exit
```

---

## 7. 防火墙和安全组配置

### 7.1 服务器防火墙（firewalld示例）

```bash
# 开放27017端口（仅内网）
sudo firewall-cmd --permanent --add-rich-rule='rule family="ipv4" source address="<102-server-ip>/32" port protocol="tcp" port="27017" accept'

# 如果有多个Runtime节点，逐个添加
sudo firewall-cmd --permanent --add-rich-rule='rule family="ipv4" source address="<102-2-server-ip>/32" port protocol="tcp" port="27017" accept'

# 允许91服务器（用于管理监控）
sudo firewall-cmd --permanent --add-rich-rule='rule family="ipv4" source address="<91-server-ip>/32" port protocol="tcp" port="27017" accept'

# 重新加载防火墙
sudo firewall-cmd --reload

# 查看规则
sudo firewall-cmd --list-all
```

### 7.2 云服务商安全组配置

在云服务商控制台配置89服务器的安全组：

**入站规则**：
```
协议: TCP
端口: 27017
来源: <102服务器内网IP>/32 (Runtime主节点)
来源: <102-2服务器内网IP>/32 (Runtime扩容节点)
来源: <91服务器内网IP>/32 (API服务器，用于管理)
描述: MongoDB for Fun AI Studio Runtime Apps
```

**重要**：不要开放 `0.0.0.0/0`（公网访问）！

---

## 8. 监控和维护

### 8.1 日志查看

```bash
# 实时查看日志
sudo tail -f /data/funai/mongo/log/mongod.log

# 查看最近的错误
sudo grep -i error /data/funai/mongo/log/mongod.log | tail -20

# 查看慢查询日志
sudo grep -i "slow query" /data/funai/mongo/log/mongod.log
```

### 8.2 磁盘空间监控

```bash
# 查看数据目录大小
du -sh /data/funai/mongo

# 查看磁盘使用情况
df -h /data/funai/mongo
```

### 8.3 数据库状态

```bash
mongosh -u admin -p your_strong_admin_password --authenticationDatabase admin
```

```javascript
// 查看服务器状态
db.serverStatus()

// 查看所有数据库
show dbs

// 查看数据库大小
db.stats()

// 查看连接数
db.serverStatus().connections
```

---

## 9. 备份策略

### 9.1 简单备份脚本

创建备份脚本 `/opt/funai/backup-mongo.sh`:

```bash
#!/bin/bash
# MongoDB 备份脚本

BACKUP_DIR="/data/funai/mongo/backups"
DATE=$(date +%Y%m%d_%H%M%S)
MONGO_USER="admin"
MONGO_PASS="your_strong_admin_password"

# 创建备份目录
mkdir -p $BACKUP_DIR

# 执行备份
mongodump \
  --username=$MONGO_USER \
  --password=$MONGO_PASS \
  --authenticationDatabase=admin \
  --out=$BACKUP_DIR/backup_$DATE

# 压缩备份
cd $BACKUP_DIR
tar -czf backup_$DATE.tar.gz backup_$DATE
rm -rf backup_$DATE

# 删除7天前的备份
find $BACKUP_DIR -name "backup_*.tar.gz" -mtime +7 -delete

echo "Backup completed: backup_$DATE.tar.gz"
```

设置权限并添加到crontab：

```bash
sudo chmod +x /opt/funai/backup-mongo.sh

# 每天凌晨2点执行备份
sudo crontab -e
# 添加：
# 0 2 * * * /opt/funai/backup-mongo.sh >> /data/funai/mongo/log/backup.log 2>&1
```

### 9.2 恢复数据

```bash
# 解压备份
cd /data/funai/mongo/backups
tar -xzf backup_20260128_020000.tar.gz

# 恢复所有数据库
mongorestore \
  --username=admin \
  --password=your_strong_admin_password \
  --authenticationDatabase=admin \
  backup_20260128_020000/

# 恢复单个数据库
mongorestore \
  --username=admin \
  --password=your_strong_admin_password \
  --authenticationDatabase=admin \
  --db=db_20000254 \
  backup_20260128_020000/db_20000254/
```

---

## 10. 性能优化建议

### 10.1 内存配置

```yaml
# /etc/mongod.conf
storage:
  wiredTiger:
    engineConfig:
      # 缓存大小 = (总内存 - 1GB) * 0.5
      # 例如：8GB服务器配置为 3GB
      cacheSizeGB: 3
```

### 10.2 连接数限制

```yaml
# /etc/mongod.conf
net:
  maxIncomingConnections: 1000  # 根据实际应用数量调整
```

### 10.3 操作系统优化

```bash
# 禁用透明大页（Transparent Huge Pages）
echo never | sudo tee /sys/kernel/mm/transparent_hugepage/enabled
echo never | sudo tee /sys/kernel/mm/transparent_hugepage/defrag

# 永久生效（添加到 /etc/rc.local 或 systemd 服务）
cat >> /etc/rc.local <<EOF
echo never > /sys/kernel/mm/transparent_hugepage/enabled
echo never > /sys/kernel/mm/transparent_hugepage/defrag
EOF

sudo chmod +x /etc/rc.local
```

---

## 11. 常见问题排查

### 11.1 无法启动

```bash
# 查看详细错误
sudo journalctl -u mongod -n 50

# 检查配置文件语法
mongod --config /etc/mongod.conf --configExpand

# 检查端口占用
sudo netstat -tulnp | grep 27017

# 检查数据目录权限
ls -la /data/funai/mongo
```

### 11.2 连接被拒绝

```bash
# 检查MongoDB是否运行
sudo systemctl status mongod

# 检查监听地址
sudo netstat -tulnp | grep mongod

# 测试端口连通性（从102服务器）
telnet <89-server-ip> 27017
nc -zv <89-server-ip> 27017
```

### 11.3 认证失败

```javascript
// 检查用户信息
use admin
db.system.users.find()

// 重置用户密码（管理员操作）
db.changeUserPassword("funai_app", "new_password")
```

---

## 12. 下一步配置（Runtime-Agent集成）

完成MongoDB部署后，需要在Runtime节点配置连接参数。

在 `fun-ai-studio-runtime/config/runtime.env` 中添加：

```bash
# MongoDB配置
MONGO_HOST=<89-server-ip>
MONGO_PORT=27017
MONGO_USERNAME=funai_app
MONGO_PASSWORD=funai_app_password_2026
MONGO_AUTH_SOURCE=admin

# 连接URI模板（runtime-agent会根据appId生成完整URI）
# mongodb://${MONGO_USERNAME}:${MONGO_PASSWORD}@${MONGO_HOST}:${MONGO_PORT}/db_${appId}?authSource=${MONGO_AUTH_SOURCE}
```

---

## 13. 完成检查清单

部署完成后，确认以下事项：

- [ ] MongoDB服务正常运行（`systemctl status mongod`）
- [ ] 已创建管理员用户和应用用户
- [ ] 已启用认证（`authorization: enabled`）
- [ ] 数据目录已持久化到 `/data/funai/mongo/data`
- [ ] 日志目录在 `/data/funai/mongo/log`
- [ ] 备份目录在 `/data/funai/mongo/backups`
- [ ] 防火墙/安全组已配置（只允许102等Runtime节点访问）
- [ ] 从102服务器可以成功连接
- [ ] 已设置开机自启动（`systemctl enable mongod`）
- [ ] 已配置备份计划（每日备份）
- [ ] 已在Runtime配置文件中添加连接参数

---

## 附录：快速参考命令

```bash
# 启动/停止/重启
sudo systemctl start mongod
sudo systemctl stop mongod
sudo systemctl restart mongod

# 查看状态和日志
sudo systemctl status mongod
sudo tail -f /var/log/mongodb/mongod.log

# 连接MongoDB
mongosh -u admin -p <password> --authenticationDatabase admin

# 备份
mongodump -u admin -p <password> --authenticationDatabase admin --out=/backup/$(date +%Y%m%d)

# 恢复
mongorestore -u admin -p <password> --authenticationDatabase admin /backup/20260128

# 查看磁盘使用
du -sh /data/funai/mongo/*
df -h /data/funai/mongo
```

