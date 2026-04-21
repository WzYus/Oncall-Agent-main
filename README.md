# OnCall-Agent

> 基于 **Spring Boot（Java）+ LangGraph（Python）** 的智能告警分析与运维报告系统

## 📖 项目简介

本项目是一个面向生产环境的智能运维（AIOps）助手，采用 **Java 后端 + Python Agent** 的混合架构：

- **Java 后端**：负责业务接口、工具实现（Prometheus 告警查询、CLS 日志检索、Milvus 文档向量检索），并提供 SSE 流式网关。
- **Python Agent**：基于 LangGraph 实现 **Supervisor → Planner → Executor** 多智能体协作，自动分析 Prometheus 真实告警并生成结构化运维报告。

## 🚀 核心特性

- ✅ **真实监控集成**：对接 Prometheus 实时告警，支持自定义业务告警规则。
- ✅ **多 Agent 协作**：Planner 负责规划、Executor 负责工具执行、Supervisor 负责调度与僵局检测。
- ✅ **自动化报告生成**：基于真实告警和日志数据，输出包含根因分析、处理建议的 Markdown 报告。
- ✅ **流式响应**：通过 SSE 将分析进度实时推送至前端，提升交互体验。
- ✅ **容器化部署**：提供完整的 `docker-compose.yml`，一键启动 Java、Python、Prometheus、Milvus 全家桶。
- ✅ **工具生态**：内置时间查询、Prometheus 告警、CLS 日志检索、内部文档 RAG 等工具。

## 🛠️ 技术栈

| 模块               | 技术                                     |
| ------------------ | ---------------------------------------- |
| **Java 后端**      | Spring Boot 3.2、Spring AI Alibaba、OkHttp |
| **Python Agent**   | FastAPI、LangGraph、LangChain、Ollama（可选） |
| **向量数据库**     | Milvus                                   |
| **监控**           | Prometheus（含自定义告警规则）             |
| **日志服务**       | 腾讯云 CLS（支持 Mock 与真实模式切换）     |
| **大模型**         | 阿里云 DashScope（或本地 Ollama）          |
| **容器编排**       | Docker Compose                           |

## 📦 项目结构
```text
OnCall-Agent-main/
├── src/main/java/org/example/
│   ├── controller/
│   │   ├── ChatController.java        # 对话与 AIOps 入口、SSE 网关
│   │   └── AgentToolController.java   # 工具接口（供 Python Agent 调用）
│   ├── service/                       # 业务服务（RAG、向量检索、对话）
│   ├── agent/tool/
│   │   ├── DateTimeTools.java
│   │   ├── InternalDocsTools.java
│   │   ├── QueryMetricsTools.java      # Prometheus 告警查询
│   │   └── QueryLogsTools.java         # CLS 日志查询
│   └── config/                         # 配置类
├── src/main/resources/
│   ├── static/                         # 前端测试界面
│   └── application.yml                 # 主配置文件
├── docker-compose.yml                  # 容器编排配置
├── prometheus.yml                      # Prometheus 抓取配置
├── business_alerts.yml                 # 业务告警规则
├── Dockerfile                          # Java 应用镜像构建
└── aiops-python-agent/                 # Python Agent（Git 子模块）
```

### Python Agent (aiops-python-agent/)
```text
aiops-python-agent/
├── agent/
│   ├── supervisor.py    # LangGraph 工作流定义
│   ├── prompts.py       # 提示词模板
│   └── tools.py         # Python 工具（回调 Java API）
├── main.py              # FastAPI 入口
├── requirements.txt
└── Dockerfile           # Python 应用镜像构建
```

## 📡 核心接口

| 接口                            | 方法 | 说明                                                         |
| ------------------------------- | ---- | ------------------------------------------------------------ |
| `/api/chat`                     | POST | 普通对话（支持工具调用），返回完整结果                        |
| `/api/chat_stream`              | POST | 流式对话，SSE 输出，支持多轮对话与工具调用                    |
| `/api/ai_ops`                   | POST | **智能运维分析**，SSE 流式返回《告警分析报告》                |
| `/api/chat/clear`               | POST | 清空会话历史                                                 |
| `/api/upload`                   | POST | 上传文档并向量化（用于 RAG 知识库）                          |
| `/actuator/prometheus`          | GET  | 暴露应用指标，供 Prometheus 抓取                             |
| `/api/tools/*`                  | POST | **内部工具接口**，供 Python Agent 调用（不直接对外暴露）       |

## ⚙️ 核心配置说明

### 1. 容器化部署下的关键配置（`application.yml`）

```yaml
milvus:
  host: standalone            # 容器内服务名
  port: 19530

prometheus:
  base-url: http://prometheus:9090   # 容器内服务名
  mock-enabled: false               # 真实模式

agent:
  python:
    url: http://python-agent:5000   # 容器内服务名

cls:
  mock-enabled: true                # 可切换真实 CLS
2. 环境变量
变量名	说明
DASHSCOPE_API_KEY	阿里云 DashScope API Key（必填）
TENCENT_CLS_SECRET_ID	腾讯云 CLS SecretId（真实日志模式时需要）
TENCENT_CLS_SECRET_KEY	腾讯云 CLS SecretKey
TENCENT_CLS_TOPIC_ID	腾讯云 CLS 日志主题 ID
JAVA_BACKEND_URL	Python Agent 访问 Java 后端的地址（容器内自动注入）
可通过项目根目录下的 .env 文件统一管理上述变量。

🚀 快速开始（全容器化部署）
1. 克隆仓库并初始化子模块
bash
git clone https://github.com/WzYus/Oncall-Agent-main.git
cd Oncall-Agent-main
git submodule update --init --recursive
2. 设置环境变量
在项目根目录创建 .env 文件，填入必要信息：

text
DASHSCOPE_API_KEY=sk-xxxx
TENCENT_CLS_SECRET_ID=xxxx
TENCENT_CLS_SECRET_KEY=xxxx
TENCENT_CLS_TOPIC_ID=xxxx
3. 一键启动所有服务
bash
docker-compose up -d --build
启动后将包含以下容器：

oncall-java：Java 后端（端口 9900）

aiops-python：Python Agent（端口 5000）

prometheus：监控与告警（端口 9090）

milvus-standalone、milvus-etcd、milvus-minio：向量数据库全家桶

milvus-attu：Milvus 可视化管理界面（端口 8000）

4. 访问服务
前端测试界面：http://localhost:9900

Prometheus UI：http://localhost:9090

Milvus Attu：http://localhost:8000

点击前端界面中的“AI Ops 分析”按钮，即可触发多 Agent 协作流程，生成流式报告。

5. 本地开发模式（非容器化）
若需要在 IDE 中分别运行 Java 和 Python：

启动 Docker 基础服务（Milvus、Prometheus）：

bash
docker-compose up -d etcd minio standalone attu prometheus
在 IDE 中启动 Java 应用（org.example.Main），确保 application.yml 中地址指向 localhost（如 milvus.host=localhost、prometheus.base-url=http://localhost:9090）。

在 PyCharm 中运行 Python Agent 的 main.py，并修改 tools.py 中的 JAVA_BACKEND_URL 为 http://localhost:9900。

📊 AIOps 工作流示意
text
用户请求 → Java (ChatController) 转发 → Python Agent (FastAPI)
         ↓
Supervisor 调度 → Planner 输出 JSON 决策 (EXECUTE)
         ↓
Executor 强制调用工具 (query_prometheus_alerts) → Java 工具接口
         ↓
获取真实 Prometheus 告警 → Planner 基于证据输出 FINISH
         ↓
生成 Markdown 报告 → Java 网关 → 前端 SSE 流式展示
📄 License
MIT License

版本: v2.0.0（混合架构）
作者: WzYus