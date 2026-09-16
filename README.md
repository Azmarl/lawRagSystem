# 律法知识检索系统

律法知识检索系统是一个面向法律知识管理的 RAG（检索增强生成）应用，支持文档处理、知识库构建、混合检索和智能问答。

## 核心能力

- 上传、解析和管理法律文档
- 基于 Elasticsearch 构建关键词与向量混合检索
- 基于知识库内容进行流式对话
- 支持组织标签和多租户数据隔离
- 支持文档向量化、异步处理和检索结果溯源
- 支持聊天记录持久化与断线恢复

## 技术栈

### 后端

- Java 17
- Spring Boot
- Spring Data JPA
- Spring Security 和 JWT
- WebSocket / WebFlux
- MySQL
- Redis
- Elasticsearch
- Kafka
- MinIO
- Apache Tika

### 前端

- Vue 3
- TypeScript
- Vite
- Pinia
- UnoCSS

## 本地开发

### 环境要求

- Java 17
- Maven 3.8.6+
- Node.js 18.20.0+
- pnpm 8.7.0+
- MySQL 8+
- Redis 7+
- Elasticsearch 8+
- Kafka 3+
- MinIO

### 启动后端

```bash
mvn spring-boot:run
```

后端默认运行在 `http://localhost:8081`。

### 启动前端

```bash
cd frontend
pnpm install
pnpm dev
```

前端默认运行在 `http://localhost:9527`。

## 配置

请根据本地环境配置根目录 `.env`、后端 `application*.yml` 和前端 `.env*` 文件。生产环境请使用独立的密钥、数据库密码和第三方服务凭据，不要将真实凭据提交到仓库。

## 项目结构

```text
src/main/java/     Spring Boot 后端
src/main/resources/后端配置和资源
frontend/src/       Vue 前端
frontend/public/    前端公共资源
docs/               部署、测试和技术文档
scripts/            辅助脚本
```

## 许可

本项目使用 Apache-2.0 许可证，具体条款请参阅 [LICENSE](LICENSE)。
