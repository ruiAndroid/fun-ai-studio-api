# 标准监控方案（Prometheus + Alertmanager + Grafana，systemd）

本方案将原“最小可落地监控方案”升级为**标准方案**：

- **全套用 systemd 管理**（service / timer / override），便于运维与审计
- **Prometheus + Alertmanager + Grafana**（监控、告警、可视化）
- **node_exporter**（宿主机资源）
- **blackbox_exporter（可选，但推荐）**：探测关键服务存活（HTTP/TCP）
- **Podman 容器指标（可选，按需）**：基于 node_exporter textfile + systemd timer（不依赖 cAdvisor）

> 约定：Prometheus/Grafana/Alertmanager/blackbox 都部署在 **API 服务器（91，小机）**，并且只监听 `127.0.0.1`；通过 SSH 隧道访问 UI。

---

## 0. 适用范围与目标

适用场景：当前采用 **6 台模式**（API / workspace-dev / Deploy / Runner / Runtime / Git），希望优先覆盖：

- **宿主机资源**：CPU/内存/磁盘/网络/load/inode
- **关键服务存活**（HTTP 探活）：
  - Agent：`88:80`（建议提供 `/internal/health`；通常由 `91` 入口通过 `/fun-agent/**` 转发）
  - Deploy：`7002`（`/internal/health`）
  - runtime-agent：`7005`（`/internal/health`）
  - Runner：无对外端口（建议用 systemd/日志监控）
- **容器维度（可选）**：workspace-dev/runtime 上 Podman 运行的关键容器资源快照

---

## 1. 组件清单与端口矩阵（标准）

### 1.1 API 服务器（91，小机）

- **Prometheus**：`127.0.0.1:9090`
- **Alertmanager**：`127.0.0.1:9093`
- **Grafana**：`127.0.0.1:3000`
- **blackbox_exporter（推荐）**：`127.0.0.1:9115`
- **node_exporter（可选，但建议也装）**：`:9100`

### 1.2 其他服务器（87/100/101/102/103）

- **node_exporter**：`:9100`
- **Podman textfile 指标（可选）**：仍走 node_exporter 的 textfile 目录（无需额外端口）

> 若你们现网包含 **Agent Node（88）**：同样按“其他服务器”安装 `node_exporter`，并在 Prometheus 配置里追加 `172.21.138.88:9100` 即可。

---

## 2. 安全组 / 防火墙放行（标准）

Prometheus 在 API(91) 上抓取其它机器指标，所以其它机器需要对 **91** 开放 exporter 端口（建议严格来源）。

### 2.1 需要放行的入站（建议全部只允许来源 `172.21.138.91/32`）

- **所有需要监控的机器（87/100/101/102/103）**：
  - `9100/tcp`：node_exporter

### 2.2 API 服务器（91）

- 不需要对公网开放 `9090/9093/3000/9115`（全部仅本机监听）
- 若要从浏览器访问 UI：使用 SSH 隧道（见第 8 节）

---

## 3. 目录约定（统一）

建议统一落盘：

- **Prometheus**
  - 配置：`/data/funai/monitoring/prometheus/prometheus.yml`
  - 数据：`/data/funai/monitoring/prometheus/data`
  - 规则：`/data/funai/monitoring/prometheus/rules/*.yml`
- **Alertmanager**
  - 配置：`/data/funai/monitoring/alertmanager/alertmanager.yml`
  - 数据：`/data/funai/monitoring/alertmanager/data`
- **Grafana**
  - 数据：`/data/funai/monitoring/grafana`
- **blackbox_exporter**
  - 配置：`/data/funai/monitoring/blackbox/blackbox.yml`

创建目录：

```bash
mkdir -p /data/funai/monitoring/{prometheus/{data,rules},alertmanager/{data},grafana,blackbox}
```

---

## 4. 所有节点：node_exporter（systemd）

### 4.1 安装（建议二进制安装）

> 版本策略：建议使用 node_exporter “最新稳定版”。内部环境若需要固定版本，请在变量中固定。

```bash
set -e
NE_VERSION="${NE_VERSION:-1.8.2}"   # 如需固定版本，在这里改

useradd -r -s /sbin/nologin node_exporter || true

mkdir -p /opt/node_exporter
cd /opt/node_exporter

curl -L -o node_exporter.tar.gz \
  "https://github.com/prometheus/node_exporter/releases/download/v${NE_VERSION}/node_exporter-${NE_VERSION}.linux-amd64.tar.gz"
tar -zxf node_exporter.tar.gz
cp -f "node_exporter-${NE_VERSION}.linux-amd64/node_exporter" /usr/local/bin/node_exporter
chmod +x /usr/local/bin/node_exporter
```

### 4.2 systemd 服务（标准写法）

创建：`/etc/systemd/system/node_exporter.service`

```ini
[Unit]
Description=Prometheus Node Exporter
After=network.target

[Service]
Type=simple
User=node_exporter
Group=node_exporter
ExecStart=/usr/local/bin/node_exporter \
  --web.listen-address=:9100 \
  --collector.systemd \
  --collector.textfile.directory=/var/lib/node_exporter/textfile_collector
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
```

创建 textfile 目录并授权：

```bash
mkdir -p /var/lib/node_exporter/textfile_collector
chown -R node_exporter:node_exporter /var/lib/node_exporter
```

启动：

```bash
systemctl daemon-reload
systemctl enable --now node_exporter
systemctl status node_exporter --no-pager -l
```

> 如果你的发行版上 `--collector.systemd` 出现权限/DBus 问题：先去掉该参数跑通基础监控，再补齐。

---

## 5. workspace-dev / runtime：Podman 容器指标（可选，systemd timer）

本仓库提供脚本：`./podman-textfile-metrics.sh`，它会把 Podman 容器快照写入 node_exporter 的 textfile 目录。

### 5.1 安装脚本

将脚本复制到机器上（建议路径固定）：

```bash
install -m 0755 ./podman-textfile-metrics.sh /usr/local/bin/funai-podman-textfile-metrics
```

### 5.2 systemd service + timer

创建：`/etc/systemd/system/funai-podman-textfile-metrics.service`

```ini
[Unit]
Description=Export Podman metrics to node_exporter textfile collector
After=network.target node_exporter.service

[Service]
Type=oneshot
Environment=OUT_DIR=/var/lib/node_exporter/textfile_collector
Environment=OUT_FILE=/var/lib/node_exporter/textfile_collector/podman_containers.prom
ExecStart=/usr/local/bin/funai-podman-textfile-metrics
```

创建：`/etc/systemd/system/funai-podman-textfile-metrics.timer`

```ini
[Unit]
Description=Run Podman metrics exporter every 30s

[Timer]
OnBootSec=30s
OnUnitActiveSec=30s
AccuracySec=5s
Unit=funai-podman-textfile-metrics.service

[Install]
WantedBy=timers.target
```

启动 timer：

```bash
systemctl daemon-reload
systemctl enable --now funai-podman-textfile-metrics.timer
systemctl list-timers --all | grep funai-podman-textfile-metrics || true
```

验证：

```bash
curl -sS http://127.0.0.1:9100/metrics | grep -E '^podman_container_' | head
```

> 注意：脚本默认只导出关键容器名（见脚本内 `PODMAN_METRICS_NAME_REGEX`）；如需更宽/更窄请按你们命名调整。

---

## 6. API 服务器（91）：Prometheus（systemd）

### 6.1 安装 Prometheus（二进制）

```bash
set -e
PROM_VERSION="${PROM_VERSION:-2.54.1}"  # 如需固定版本，在这里改

useradd -r -s /sbin/nologin prometheus || true

mkdir -p /opt/prometheus
cd /opt/prometheus

curl -L -o prometheus.tar.gz \
  "https://github.com/prometheus/prometheus/releases/download/v${PROM_VERSION}/prometheus-${PROM_VERSION}.linux-amd64.tar.gz"
tar -zxf prometheus.tar.gz
cp -f "prometheus-${PROM_VERSION}.linux-amd64/prometheus" /usr/local/bin/prometheus
cp -f "prometheus-${PROM_VERSION}.linux-amd64/promtool" /usr/local/bin/promtool
chmod +x /usr/local/bin/prometheus /usr/local/bin/promtool

chown -R prometheus:prometheus /data/funai/monitoring/prometheus
```

### 6.2 Prometheus 配置（标准示例）

保存为：`/data/funai/monitoring/prometheus/prometheus.yml`

```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

rule_files:
  - "/data/funai/monitoring/prometheus/rules/*.yml"

alerting:
  alertmanagers:
    - static_configs:
        - targets: ["127.0.0.1:9093"]

scrape_configs:
  # -----------------------------
  # Prometheus 自身
  # -----------------------------
  - job_name: "prometheus"
    static_configs:
      - targets: ["127.0.0.1:9090"]

  # -----------------------------
  # Alertmanager（用于监控自身健康状态）
  # -----------------------------
  - job_name: "alertmanager"
    static_configs:
      - targets: ["127.0.0.1:9093"]

  # -----------------------------
  # 宿主机指标（node_exporter）
  # -----------------------------
  - job_name: "node"
    static_configs:
      - targets:
          - "172.21.138.91:9100"   # API(91)
          - "172.21.138.88:9100"   # Agent(88)
          - "172.21.138.87:9100"   # workspace-dev(87)
          - "172.21.138.100:9100"  # Deploy(100)
          - "172.21.138.101:9100"  # Runner(101)
          - "172.21.138.102:9100"  # Runtime(102)
          - "172.21.138.103:9100"  # Git(103)

  # -----------------------------
  # blackbox 探测（HTTP 存活）
  # -----------------------------
  - job_name: "blackbox_http"
    metrics_path: /probe
    params:
      module: [http_2xx]
    static_configs:
      - targets:
          # Agent（优先探“91 入口 → Nginx 转发 → 88”的整条链路）
          # 前提：91 上 Nginx 已配置 /fun-agent/** 反代到 88（见 doc/domains/server/small-nginx-workspace-split.conf.example）
          - http://127.0.0.1/fun-agent/internal/health
          - http://172.21.138.100:7002/internal/health   # Deploy
          - http://172.21.138.102:7005/internal/health   # runtime-agent
    relabel_configs:
      - source_labels: [__address__]
        target_label: __param_target
      - source_labels: [__param_target]
        target_label: instance
      - target_label: __address__
        replacement: 127.0.0.1:9115  # blackbox_exporter

  # -----------------------------
  # （可选）Spring Boot 指标（/actuator/prometheus）
  # 说明：本项目目前未默认引入 actuator/micrometer；如你们后续启用，再打开此 job
  # -----------------------------
  # - job_name: "funai_api"
  #   metrics_path: "/actuator/prometheus"
  #   static_configs:
  #     - targets: ["172.21.138.91:8080"]
```

安装告警规则（标准版）：

- 将本仓库 `./monitoring-prometheus-alerts-standard.yml` 复制到：
  - `/data/funai/monitoring/prometheus/rules/alerts.yml`

校验配置：

```bash
promtool check config /data/funai/monitoring/prometheus/prometheus.yml
promtool check rules /data/funai/monitoring/prometheus/rules/alerts.yml
```

### 6.3 systemd 服务（Prometheus 仅监听本机）

创建：`/etc/systemd/system/prometheus.service`

```ini
[Unit]
Description=Prometheus
After=network.target

[Service]
Type=simple
User=prometheus
Group=prometheus
ExecStart=/usr/local/bin/prometheus \
  --web.listen-address=127.0.0.1:9090 \
  --config.file=/data/funai/monitoring/prometheus/prometheus.yml \
  --storage.tsdb.path=/data/funai/monitoring/prometheus/data \
  --storage.tsdb.retention.time=15d
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
```

启动：

```bash
systemctl daemon-reload
systemctl enable --now prometheus
systemctl status prometheus --no-pager -l
curl -sS http://127.0.0.1:9090/-/healthy
```

---

## 7. API 服务器（91）：Alertmanager（systemd）

### 7.1 安装 Alertmanager（二进制）

```bash
set -e
AM_VERSION="${AM_VERSION:-0.28.0}"  # 如需固定版本，在这里改

useradd -r -s /sbin/nologin alertmanager || true

mkdir -p /opt/alertmanager
cd /opt/alertmanager

curl -L -o alertmanager.tar.gz \
  "https://github.com/prometheus/alertmanager/releases/download/v${AM_VERSION}/alertmanager-${AM_VERSION}.linux-amd64.tar.gz"
tar -zxf alertmanager.tar.gz

cp -f "alertmanager-${AM_VERSION}.linux-amd64/alertmanager" /usr/local/bin/alertmanager
cp -f "alertmanager-${AM_VERSION}.linux-amd64/amtool" /usr/local/bin/amtool
chmod +x /usr/local/bin/alertmanager /usr/local/bin/amtool

chown -R alertmanager:alertmanager /data/funai/monitoring/alertmanager
```

### 7.2 Alertmanager 配置（示例）

保存为：`/data/funai/monitoring/alertmanager/alertmanager.yml`

```yaml
global:
  resolve_timeout: 5m

route:
  receiver: "default"
  group_by: ["alertname", "instance", "job"]
  group_wait: 30s
  group_interval: 5m
  repeat_interval: 2h

receivers:
  - name: "default"
    # 先留空也可以（Prometheus 能正常触发告警、在 UI 中可见）
    # 真正发通知可按你们现网选择：
    # - webhook（企业微信/钉钉/自建网关）
    # - email（SMTP）
    #
    # email_configs:
    #   - to: "oncall@example.com"
    #     from: "alert@example.com"
    #     smarthost: "smtp.example.com:465"
    #     auth_username: "alert@example.com"
    #     auth_password: "xxxxx"
    #     require_tls: false
```

### 7.3 systemd 服务（Alertmanager 仅监听本机）

创建：`/etc/systemd/system/alertmanager.service`

```ini
[Unit]
Description=Prometheus Alertmanager
After=network.target

[Service]
Type=simple
User=alertmanager
Group=alertmanager
ExecStart=/usr/local/bin/alertmanager \
  --web.listen-address=127.0.0.1:9093 \
  --config.file=/data/funai/monitoring/alertmanager/alertmanager.yml \
  --storage.path=/data/funai/monitoring/alertmanager/data
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
```

启动：

```bash
systemctl daemon-reload
systemctl enable --now alertmanager
systemctl status alertmanager --no-pager -l
curl -sS http://127.0.0.1:9093/-/ready
```

---

## 8. API 服务器（91）：blackbox_exporter（推荐，systemd）

### 8.1 安装 blackbox_exporter

```bash
set -e
BB_VERSION="${BB_VERSION:-0.27.0}"  # 如需固定版本，在这里改

useradd -r -s /sbin/nologin blackbox || true

mkdir -p /opt/blackbox_exporter
cd /opt/blackbox_exporter

curl -L -o blackbox.tar.gz \
  "https://github.com/prometheus/blackbox_exporter/releases/download/v${BB_VERSION}/blackbox_exporter-${BB_VERSION}.linux-amd64.tar.gz"
tar -zxf blackbox.tar.gz
cp -f "blackbox_exporter-${BB_VERSION}.linux-amd64/blackbox_exporter" /usr/local/bin/blackbox_exporter
chmod +x /usr/local/bin/blackbox_exporter

chown -R blackbox:blackbox /data/funai/monitoring/blackbox
```

### 8.2 配置

保存为：`/data/funai/monitoring/blackbox/blackbox.yml`

```yaml
modules:
  http_2xx:
    prober: http
    timeout: 5s
    http:
      preferred_ip_protocol: "ip4"
      follow_redirects: true
      valid_http_versions: ["HTTP/1.1", "HTTP/2.0"]
```

### 8.3 systemd 服务（仅监听本机）

创建：`/etc/systemd/system/blackbox_exporter.service`

```ini
[Unit]
Description=Prometheus Blackbox Exporter
After=network.target

[Service]
Type=simple
User=blackbox
Group=blackbox
ExecStart=/usr/local/bin/blackbox_exporter \
  --web.listen-address=127.0.0.1:9115 \
  --config.file=/data/funai/monitoring/blackbox/blackbox.yml
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
```

启动：

```bash
systemctl daemon-reload
systemctl enable --now blackbox_exporter
systemctl status blackbox_exporter --no-pager -l
```

---

## 9. API 服务器（91）：Grafana（systemd，推荐包安装）

Grafana 建议走官方仓库/发行版包安装（自带 systemd 服务 `grafana-server`），避免自己管理二进制与依赖。

### 9.1 安装后绑定到 127.0.0.1（不对公网）

编辑 `grafana.ini`（常见位置之一；以你们发行版为准）：

- `/etc/grafana/grafana.ini`

确保：

```ini
[server]
http_addr = 127.0.0.1
http_port = 3000
```

启动：

```bash
systemctl enable --now grafana-server
systemctl status grafana-server --no-pager -l
```

### 9.2 添加数据源与导入看板

- 添加 Prometheus 数据源：`http://127.0.0.1:9090`
- 推荐导入社区 Dashboard：
  - Node Exporter Full：`1860`
  - Blackbox Exporter（HTTP 探活）：在 Grafana.com 搜索 `blackbox exporter`

---

## 10. 访问方式（不开放公网）

### 10.1 Prometheus

```bash
ssh -L 9090:127.0.0.1:9090 root@47.93.150.220
```

浏览器访问：`http://127.0.0.1:9090`

### 10.2 Alertmanager

```bash
ssh -L 9093:127.0.0.1:9093 root@47.93.150.220
```

浏览器访问：`http://127.0.0.1:9093`

### 10.3 Grafana

```bash
ssh -L 3000:127.0.0.1:3000 root@47.93.150.220
```

浏览器访问：`http://127.0.0.1:3000`

---

## 11. 告警规则（标准版）

本仓库提供标准告警规则示例：

- `./monitoring-prometheus-alerts-standard.yml`

落盘到：

- `/data/funai/monitoring/prometheus/rules/alerts.yml`

然后重启 Prometheus（简单粗暴，适合初期）：

```bash
systemctl restart prometheus
```

---

## 12. （可选增强）Spring Boot 应用指标（/actuator/prometheus）

当前 `funaistudioapi` **未默认引入** actuator + Prometheus registry。若你们希望把 API QPS/RT/JVM 等也纳入 Grafana：

- 需要引入：
  - `spring-boot-starter-actuator`
  - `micrometer-registry-prometheus`
- 并在 `application-prod.properties` 配置开启 `/actuator/prometheus`
- 然后在 Prometheus 里打开第 6.2 节 `funai_api` job

> 这部分属于“应用可观测性”，建议单独开一篇文档沉淀指标口径与看板/告警阈值。

