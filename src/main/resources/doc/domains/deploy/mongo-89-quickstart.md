# 89服务器 MongoDB 快速部署（检查清单）

> 本文档是 [mongo-89-deployment.md](./mongo-89-deployment.md) 的快速执行版本，适合运维人员按步骤快速部署。

---

## 📋 部署前准备

- [ ] 确认89服务器系统类型（CentOS/RHEL 或 Ubuntu/Debian）
- [ ] 确认89服务器内网IP: `__________`
- [ ] 确认102服务器内网IP: `__________`
- [ ] 确认91服务器内网IP: `__________`
- [ ] 准备强密码（管理员和应用用户）

---

## 🚀 快速部署步骤

### 1. 安装MongoDB（选择对应系统）

**先检查系统版本：**

```bash
cat /etc/redhat-release
```

**系统版本对应：**
- CentOS/RHEL 7 → 使用 `redhat/7`
- CentOS/RHEL 8 → 使用 `redhat/8`
- CentOS/RHEL 9 → 使用 `redhat/9`
- **Alibaba Cloud Linux 3** → 使用 `redhat/8`
- **Alibaba Cloud Linux 2** → 使用 `redhat/7`

---

**CentOS/RHEL/Alibaba Cloud Linux:**

```bash
# 添加仓库（注意：根据系统版本修改下面的'8'为对应版本号）
# Alibaba Cloud Linux 3 使用 8
cat > /etc/yum.repos.d/mongodb-org-7.0.repo <<'EOF'
[mongodb-org-7.0]
name=MongoDB Repository
baseurl=https://repo.mongodb.org/yum/redhat/8/mongodb-org/7.0/x86_64/
gpgcheck=1
enabled=1
gpgkey=https://www.mongodb.org/static/pgp/server-7.0.asc
EOF

# 清除缓存
yum clean all

# 安装MongoDB 7.0.14（MongoDB 7.0使用mongosh，无需指定mongodb-org-shell）
yum install -y mongodb-org-7.0.14 \
  mongodb-org-database-7.0.14 \
  mongodb-org-server-7.0.14 \
  mongodb-org-mongos-7.0.14 \
  mongodb-org-tools-7.0.14

# 锁定版本（防止自动升级）
sed -i '/\[mongodb-org-7.0\]/a exclude=mongodb-org,mongodb-org-database,mongodb-org-server,mongodb-mongosh,mongodb-org-mongos,mongodb-org-tools' /etc/yum.conf

# 验证版本
mongod --version
mongosh --version
```

**如果遇到404错误：** 说明版本号不对，回到第一步检查系统版本，修改仓库配置中的版本号后执行 `yum clean all` 重试。

**如果遇到下载超时（Curl error 28）：** 用更大的超时/重试重跑安装（推荐）：

```bash
yum clean all
yum clean packages

yum install -y \
  --setopt=timeout=600 \
  --setopt=retries=20 \
  mongodb-org-7.0.14 \
  mongodb-org-database-7.0.14 \
  mongodb-org-server-7.0.14 \
  mongodb-org-mongos-7.0.14 \
  mongodb-org-tools-7.0.14
```

**如果你切到镜像源后遇到 checksum doesn't match：** 多半是镜像站元数据不同步，建议回退到官方源（`repo.mongodb.org`）并用上面的超时参数安装。

---

**Ubuntu/Debian:**

```bash
# 导入密钥
curl -fsSL https://www.mongodb.org/static/pgp/server-7.0.asc | sudo gpg --dearmor -o /usr/share/keyrings/mongodb-server-7.0.gpg

# 添加源（Ubuntu 20.04）
echo "deb [ arch=amd64,arm64 signed-by=/usr/share/keyrings/mongodb-server-7.0.gpg ] https://repo.mongodb.org/apt/ubuntu focal/mongodb-org/7.0 multiverse" | sudo tee /etc/apt/sources.list.d/mongodb-org-7.0.list

# 对于Ubuntu 22.04，使用：
# echo "deb [ arch=amd64,arm64 signed-by=/usr/share/keyrings/mongodb-server-7.0.gpg ] https://repo.mongodb.org/apt/ubuntu jammy/mongodb-org/7.0 multiverse" | sudo tee /etc/apt/sources.list.d/mongodb-org-7.0.list

# 安装MongoDB 7.0.14
sudo apt-get update
sudo apt-get install -y mongodb-org=7.0.14 \
  mongodb-org-database=7.0.14 \
  mongodb-org-server=7.0.14 \
  mongodb-org-mongos=7.0.14 \
  mongodb-org-tools=7.0.14

# 锁定版本（防止自动升级）
echo "mongodb-org hold" | sudo dpkg --set-selections
echo "mongodb-org-database hold" | sudo dpkg --set-selections
echo "mongodb-org-server hold" | sudo dpkg --set-selections
echo "mongodb-mongosh hold" | sudo dpkg --set-selections
echo "mongodb-org-mongos hold" | sudo dpkg --set-selections
echo "mongodb-org-tools hold" | sudo dpkg --set-selections

# 验证版本
mongod --version
mongosh --version
```

---

### 2. 创建目录并配置

```bash
# 创建数据、日志和备份目录
sudo mkdir -p /data/funai/mongo/data
sudo mkdir -p /data/funai/mongo/log
sudo mkdir -p /data/funai/mongo/backups

# 设置权限（mongod默认以mongod用户运行）
sudo chown -R mongod:mongod /data/funai/mongo
sudo chmod 755 /data/funai/mongo
sudo chmod 755 /data/funai/mongo/data
sudo chmod 755 /data/funai/mongo/log
sudo chmod 755 /data/funai/mongo/backups
```

编辑配置文件：

```bash
sudo vim /etc/mongod.conf
```

修改以下关键配置：

```yaml
storage:
  dbPath: /data/funai/mongo/data
  journal:
    enabled: true
  wiredTiger:
    engineConfig:
      cacheSizeGB: 2  # 根据服务器内存调整

systemLog:
  destination: file
  logAppend: true
  path: /data/funai/mongo/log/mongod.log

net:
  port: 27017
  bindIp: 0.0.0.0  # 允许内网访问

processManagement:
  fork: false
  timeZoneInfo: /usr/share/zoneinfo

# 暂时不启用认证，待创建用户后再启用
#security:
#  authorization: enabled
```

---

### 3. 启动MongoDB

```bash
# 启动
sudo systemctl start mongod

# 检查状态
sudo systemctl status mongod

# 设置开机自启
sudo systemctl enable mongod

# 查看日志
sudo tail -f /data/funai/mongo/log/mongod.log
```

**检查点：** 确认状态为 `active (running)`

---

### 4. 创建管理员和应用用户

```bash
# 连接到MongoDB（无认证）
mongosh
```

在mongosh中执行：

```javascript
// 切换到admin数据库
use admin

// 创建管理员（请修改密码！）
db.createUser({
  user: "admin",
  pwd: "YOUR_STRONG_ADMIN_PASSWORD_HERE",  // ⚠️ 修改为强密码
  roles: [
    { role: "userAdminAnyDatabase", db: "admin" },
    { role: "readWriteAnyDatabase", db: "admin" },
    { role: "dbAdminAnyDatabase", db: "admin" }
  ]
})

// 创建应用用户（请修改密码！）
db.createUser({
  user: "funai_app",
  pwd: "YOUR_STRONG_APP_PASSWORD_HERE",  // ⚠️ 修改为强密码
  roles: [
    { role: "readWriteAnyDatabase", db: "admin" },
    { role: "dbAdmin", db: "admin" }
  ]
})

// 验证用户创建成功
db.system.users.find().pretty()

// 退出
exit
```

**记录密码：**
- 管理员密码: `__________`
- 应用用户密码: `__________`

---

### 5. 启用认证并重启

编辑配置文件：

```bash
sudo vim /etc/mongod.conf
```

取消注释安全配置：

```yaml
security:
  authorization: enabled
```

重启MongoDB：

```bash
sudo systemctl restart mongod
sudo systemctl status mongod
```

---

### 6. 测试连接（本机）

```bash
# 使用管理员测试
mongosh -u admin -p YOUR_STRONG_ADMIN_PASSWORD_HERE --authenticationDatabase admin

# 使用应用用户测试
mongosh -u funai_app -p YOUR_STRONG_APP_PASSWORD_HERE --authenticationDatabase admin
```

在mongosh中测试：

```javascript
// 创建测试数据库
use db_test

// 插入测试数据
db.test.insertOne({ message: "Hello from 89", timestamp: new Date() })

// 查询
db.test.find()

// 删除测试数据库
db.dropDatabase()

exit
```

**检查点：** 能够成功登录和操作

---

### 7. 配置防火墙（重要！）

**防火墙规则（firewalld示例）:**

```bash
# 允许102服务器访问（替换为实际IP）
sudo firewall-cmd --permanent --add-rich-rule='rule family="ipv4" source address="<102-IP>/32" port protocol="tcp" port="27017" accept'

# 允许91服务器访问（用于管理）
sudo firewall-cmd --permanent --add-rich-rule='rule family="ipv4" source address="<91-IP>/32" port protocol="tcp" port="27017" accept'

# 重新加载
sudo firewall-cmd --reload

# 验证规则
sudo firewall-cmd --list-all
```

**云服务商安全组：**

在云服务商控制台配置89的入站规则：
- 协议: TCP
- 端口: 27017
- 来源: 102服务器内网IP/32
- 来源: 91服务器内网IP/32

⚠️ **不要开放 0.0.0.0/0（公网）！**

---

### 8. 从102服务器测试连接

在102服务器上执行：

```bash
# 如果没有mongosh，先安装
# CentOS: sudo yum install -y mongodb-mongosh
# Ubuntu: sudo apt-get install -y mongodb-mongosh

# 测试连接（替换<89-IP>和密码）
mongosh "mongodb://funai_app:YOUR_STRONG_APP_PASSWORD_HERE@<89-IP>:27017/admin"
```

测试创建数据库：

```javascript
use db_test_from_102

db.test.insertOne({ 
  message: "Connection test from Runtime 102", 
  timestamp: new Date() 
})

db.test.find()

// 清理
db.dropDatabase()

exit
```

**检查点：** 从102能够成功连接并操作

---

### 9. 配置备份（推荐立即设置）

创建备份脚本：

```bash
sudo mkdir -p /opt/funai
sudo vim /opt/funai/backup-mongo.sh
```

脚本内容：

```bash
#!/bin/bash
BACKUP_DIR="/data/funai/mongo/backups"
DATE=$(date +%Y%m%d_%H%M%S)
MONGO_USER="admin"
MONGO_PASS="YOUR_STRONG_ADMIN_PASSWORD_HERE"  # ⚠️ 修改

mkdir -p $BACKUP_DIR

mongodump \
  --username=$MONGO_USER \
  --password=$MONGO_PASS \
  --authenticationDatabase=admin \
  --out=$BACKUP_DIR/backup_$DATE

cd $BACKUP_DIR
tar -czf backup_$DATE.tar.gz backup_$DATE
rm -rf backup_$DATE

find $BACKUP_DIR -name "backup_*.tar.gz" -mtime +7 -delete

echo "Backup completed: backup_$DATE.tar.gz"
```

设置权限和定时任务：

```bash
sudo chmod +x /opt/funai/backup-mongo.sh

# 添加crontab（每天凌晨2点）
sudo crontab -e
# 添加以下行：
# 0 2 * * * /opt/funai/backup-mongo.sh >> /data/funai/mongo/log/backup.log 2>&1
```

---

### 10. 配置Runtime-Agent连接参数

在102服务器上编辑Runtime配置：

```bash
vim /path/to/fun-ai-studio-runtime/config/runtime.env
```

添加或修改以下配置：

```bash
# MongoDB配置（89服务器）
MONGO_HOST=<89-server-ip>
MONGO_PORT=27017
MONGO_USERNAME=funai_app
MONGO_PASSWORD=YOUR_STRONG_APP_PASSWORD_HERE  # ⚠️ 使用实际密码
MONGO_AUTH_SOURCE=admin

# 连接URI模板（供runtime-agent使用）
# mongodb://${MONGO_USERNAME}:${MONGO_PASSWORD}@${MONGO_HOST}:${MONGO_PORT}/db_${appId}?authSource=${MONGO_AUTH_SOURCE}
```

重启runtime-agent使配置生效。

---

## ✅ 完成检查清单

部署完成后，逐项确认：

- [ ] MongoDB服务运行正常 (`systemctl status mongod`)
- [ ] 已创建管理员和应用用户
- [ ] 已启用认证 (`authorization: enabled`)
- [ ] 数据目录在 `/data/funai/mongo/data`
- [ ] 日志目录在 `/data/funai/mongo/log`
- [ ] 备份目录在 `/data/funai/mongo/backups`
- [ ] 防火墙只允许102和91访问27017端口
- [ ] 云服务商安全组已正确配置
- [ ] 从89本机可以用密码连接
- [ ] 从102服务器可以成功连接
- [ ] 已设置开机自启动
- [ ] 已配置每日备份任务
- [ ] 已在runtime.env中配置连接参数
- [ ] 已更新架构文档（Mongo服务器改为89）

---

## 🔧 常用运维命令

```bash
# 查看服务状态
sudo systemctl status mongod

# 查看日志
sudo tail -f /data/funai/mongo/log/mongod.log

# 重启服务
sudo systemctl restart mongod

# 查看磁盘使用
du -sh /data/funai/mongo/*
df -h /data/funai/mongo

# 连接MongoDB
mongosh -u admin -p <password> --authenticationDatabase admin

# 查看所有数据库
mongosh -u admin -p <password> --authenticationDatabase admin --eval "show dbs"

# 手动备份
/opt/funai/backup-mongo.sh

# 查看备份
ls -lh /data/funai/mongo/backups/
```

---

## 📚 相关文档

- [完整部署文档](./mongo-89-deployment.md) - 详细的安装、配置和优化指南
- [运行态Mongo方案](./runtime-mongo.md) - 架构设计和隔离策略
- [系统架构总览](../architecture/README.md) - 整体系统架构

---

## 🆘 遇到问题？

### MongoDB启动失败

```bash
# 查看详细错误
sudo journalctl -u mongod -n 50

# 检查配置文件语法
mongod --config /etc/mongod.conf --configExpand
```

### 无法从102连接

```bash
# 在102上测试端口连通性
telnet <89-IP> 27017
nc -zv <89-IP> 27017

# 在89上检查日志
sudo tail -f /data/funai/mongo/log/mongod.log

# 在89上检查监听
sudo netstat -tulnp | grep 27017
```

### 认证失败

```bash
# 检查用户
mongosh -u admin -p <password> --authenticationDatabase admin --eval "use admin; db.system.users.find()"

# 重置密码
mongosh -u admin -p <old-password> --authenticationDatabase admin
# 然后执行：db.changeUserPassword("funai_app", "new_password")
```

---

**部署完成后，请妥善保管密码信息！**

