# 最小可落地监控方案（Prometheus + Grafana）

适用场景：当前采用“双机模式”（**API 服务器（小机）** + **workspace-dev 服务器（大机）**），容器运行在 workspace-dev（Podman-docker），希望优先监控：

- 宿主机资源：CPU/内存/磁盘/网络/load/inode
- 容器资源：容器数量、每容器 CPU/内存、重启次数、PIDs、网络/磁盘 IO
- 关键服务：`workspace-node(7001)`、Verdaccio(4873) 的健康/可达性（先用探活 + 日志即可）

本方案选择：

- **Prometheus + Grafana 部署在 API 服务器（小机）**
- **Grafana 不对公网开放**，通过 **SSH 隧道**访问
- workspace-dev 只开放 **必要端口** 给 API 服务器来源（安全组收敛）

---

## 1. 组件清单（最小集）

部署位置与端口：

- **API 服务器（小机）**
  - Prometheus：`127.0.0.1:9090`
  - Grafana：`127.0.0.1:3000`
  - node_exporter：`0.0.0.0:9100`（也可只监听内网）
- **workspace-dev 服务器（大机）**
  - node_exporter：`0.0.0.0:9100`
  - cAdvisor：`0.0.0.0:8080`（容器指标导出）

> 说明：Prometheus/Grafana 只绑定到 `127.0.0.1`，避免公网暴露；通过 SSH 隧道访问即可。

---

## 2. 安全组最小放行（两台不在同 VPC，走公网互通）

### 2.1 workspace-dev（大机）入方向（建议）

只允许来自 API 服务器公网 IP 的访问：

- `9100/tcp`：node_exporter
- `8080/tcp`：cAdvisor
- （可选）`7001/tcp`：workspace-node（如果 Prometheus 要抓 `actuator/prometheus` 或你要做探活）

### 2.2 API 服务器（小机）

- 不需要对公网开放 `9090/3000`（Prometheus/Grafana 都仅本机监听）

---

## 3. API 服务器（小机）：Prometheus + Grafana（Podman-docker / docker run）

### 3.1 目录约定（建议落盘）

```bash
mkdir -p /data/funai/monitoring/prometheus
mkdir -p /data/funai/monitoring/grafana
```

Prometheus 配置文件：

- `/data/funai/monitoring/prometheus/prometheus.yml`

### 3.2 Prometheus 配置（示例）

将下方内容保存为：

`/data/funai/monitoring/prometheus/prometheus.yml`

```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

rule_files:
  - "/etc/prometheus/rules/*.yml"

scrape_configs:
  # API 服务器（小机）宿主机指标
  - job_name: "node_api"
    static_configs:
      - targets: ["127.0.0.1:9100"]

  # workspace-dev（大机）宿主机指标
  - job_name: "node_workspace_dev"
    static_configs:
      - targets: ["172.21.138.87:9100"]

  # workspace-dev（大机）容器指标（cAdvisor）
  - job_name: "cadvisor_workspace_dev"
    static_configs:
      - targets: ["172.21.138.87:8080"]

  # （可选）workspace-node 应用指标（Spring Boot actuator）
  # 需要 workspace-node 打开 actuator/prometheus
  # - job_name: "workspace_node"
  #   metrics_path: "/actuator/prometheus"
  #   static_configs:
  #     - targets: ["172.21.138.87:7001"]
```

> 你当前环境：Prometheus 从 API 服务器抓取 workspace-dev 走内网 `172.21.138.87`。
> - 若两台机器跨 VPC 走公网：填 workspace-dev 公网 IP（示例：`39.97.61.139`）
> - 若你已打通内网互通/专线：填 workspace-dev 的内网 IP（以你实际可达为准）
>
> 你当前环境已知：API 服务器公网 IP 为 `47.93.150.220`；workspace-dev 访问 API 的内网 IP 为 `172.21.138.91`（此 IP 仅说明内网链路存在，不代表 API→workspace-dev 的可达地址）。
>
> 告警规则示例见：`./monitoring-prometheus-alerts-minimal.yml`（复制到 `/data/funai/monitoring/prometheus/rules/alerts.yml`）。

### 3.3 运行 Prometheus（只监听本机 127.0.0.1）

```bash
mkdir -p /data/funai/monitoring/prometheus/rules

# 将仓库中的 rules 示例复制到落盘目录（按需调整阈值）
# cp /path/to/repo/src/main/resources/doc/domains/server/monitoring-prometheus-alerts-minimal.yml /data/funai/monitoring/prometheus/rules/alerts.yml

docker run -d --name funai-prometheus --restart=always \
  -p 127.0.0.1:9090:9090 \
  -v /data/funai/monitoring/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:Z \
  -v /data/funai/monitoring/prometheus/rules:/etc/prometheus/rules:Z \
  -v /data/funai/monitoring/prometheus:/prometheus:Z \
  prom/prometheus:v2.54.1 \
  --config.file=/etc/prometheus/prometheus.yml \
  --storage.tsdb.path=/prometheus \
  --storage.tsdb.retention.time=15d
```

### 3.4 运行 Grafana（只监听本机 127.0.0.1）

```bash
docker run -d --name funai-grafana --restart=always \
  -p 127.0.0.1:3000:3000 \
  -v /data/funai/monitoring/grafana:/var/lib/grafana:Z \
  grafana/grafana:11.2.0
```

Grafana 初始账号（默认）：

- user：`admin`
- password：`admin`（首次登录会要求修改）

### 3.5 Grafana 最小看板（推荐直接导入官方社区 Dashboard）

Grafana → `Connections` → `Data sources` 添加 Prometheus（URL：`http://127.0.0.1:9090`）。

然后导入以下 Dashboard（Grafana.com 常用 ID）：

- Node Exporter Full：`1860`
- cAdvisor / Containers：可先用关键字搜索 `cAdvisor`（不同版本 ID 可能变化）

> 说明：最小方案先用社区看板即可；后续再沉淀你们自己的业务看板（workspace-node QPS/RT/5xx/JVM 等）。

---

## 4. 两台服务器：node_exporter（宿主机指标）

两种方式任选一种（推荐 systemd 更稳，docker 也可以）。

### 4.1 方式 A：用 systemd（推荐）

安装（以实际发行版为准）：

```bash
# 下载二进制（示例；你也可以用包管理器安装）
mkdir -p /opt/node_exporter && cd /opt/node_exporter
curl -L -o node_exporter.tar.gz https://github.com/prometheus/node_exporter/releases/download/v1.8.2/node_exporter-1.8.2.linux-amd64.tar.gz
tar -zxf node_exporter.tar.gz
cp -f node_exporter-1.8.2.linux-amd64/node_exporter /usr/local/bin/node_exporter
```

创建 systemd：

`/etc/systemd/system/node_exporter.service`

```ini
[Unit]
Description=Prometheus Node Exporter
After=network.target

[Service]
Type=simple
ExecStart=/usr/local/bin/node_exporter --web.listen-address=:9100
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
```

启动：

```bash
systemctl daemon-reload
systemctl enable --now node_exporter
systemctl status node_exporter --no-pager -l
```

### 4.2 方式 B：用 docker/podman（可选）

```bash
docker run -d --name node_exporter --restart=always \
  --net=host --pid=host \
  -v /:/host:ro,rslave \
  prom/node-exporter:v1.8.2 \
  --path.rootfs=/host
```

---

## 5. workspace-dev（大机）：cAdvisor（容器指标）

> Podman-docker 环境下也可运行 cAdvisor；如果遇到权限/挂载问题，优先用 `--privileged` 验证链路，跑通后再逐步收敛权限。

```bash
docker run -d --name cadvisor --restart=always \
  -p 8080:8080 \
  --privileged \
  -v /:/rootfs:ro \
  -v /var/run:/var/run:rw \
  -v /sys:/sys:ro \
  -v /var/lib/containers:/var/lib/containers:ro \
  gcr.io/cadvisor/cadvisor:v0.49.2
```

如果你使用的是 docker 存储目录（非 podman），把 `-v /var/lib/containers` 改为 `-v /var/lib/docker`。

### 5.1 Podman-docker 环境注意点

- 若 cAdvisor 看不到容器：优先确认挂载的容器存储目录是否正确（Podman 常见为 `/var/lib/containers`）。
- 若遇到 SELinux 权限：可临时用 `--privileged` 跑通链路，再逐步收敛；必要时给挂载加 `:Z`。

---

## 6. 访问方式（不开放公网）

### 6.1 Prometheus（本机）

在本地电脑执行 SSH 隧道：

```bash
ssh -L 9090:127.0.0.1:9090 root@47.93.150.220
```

浏览器访问：`http://127.0.0.1:9090`

### 6.2 Grafana（本机）

```bash
ssh -L 3000:127.0.0.1:3000 root@47.93.150.220
```

浏览器访问：`http://127.0.0.1:3000`

---

## 7. 最小告警/关注点（建议先人工看图）

第一阶段建议先不急着做告警，先把图跑通，观察 1~3 天。

必须重点关注：

- workspace-dev 磁盘：`/data` 使用率、增速（特别是 `/data/funai/workspaces`、`/data/funai/database`、`/data/funai/verdaccio`）
- 容器数量：running 是否持续上涨不回落（可能 idle 回收失效）
- 单容器内存/CPU：是否出现异常热点用户
- `podman ps -a --size`：容器可写层膨胀（npm cache / 日志）

如果你已启用 Prometheus rules（见上文 `rules/*.yml`），也可先用最小告警规则文件：

- `./monitoring-prometheus-alerts-minimal.yml`

---

## 8. 快速排障命令（无监控也能用）

### 8.1 workspace-dev：容器数量/资源

```bash
docker ps
docker stats --no-stream
podman ps -a --size
```

### 8.2 磁盘大户定位

```bash
du -sh /data/funai/* | sort -h
```

### 8.3 服务日志

```bash
journalctl -u fun-ai-studio-api -n 200 --no-pager
journalctl -u fun-ai-studio-workspace -n 200 --no-pager
```


