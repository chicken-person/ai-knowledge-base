# AI 文档知识库问答系统

面向 Java 后端实习投递的 RAG 风格项目。默认模式使用 H2 文件数据库和本地轻量向量检索，方便在没有 MySQL、Redis、Qdrant 的电脑上直接运行；后续可以平滑替换为 MySQL + Redis + Qdrant + 大模型 API。

## 技术栈

- Java 17
- Spring Boot 3
- Spring Web / Validation / JDBC
- H2 本地文件数据库
- 轻量向量检索与 RAG 问答流程
- Docker / MySQL / Redis / Qdrant 扩展预留

## 本地运行

构建 jar：

```powershell
mvn -DskipTests package
```

启动服务：

```powershell
java -jar target/ai-knowledge-base-0.1.0.jar
```

服务默认端口：`8081`

可视化页面：

```text
http://localhost:8081/
```

健康检查：

```powershell
curl http://localhost:8081/api/health
```

## 测试账号

启动后会自动创建：

- 用户名：`demo`
- 密码：`demo123456`

## 核心接口

登录：

```powershell
curl -X POST http://localhost:8081/api/auth/login `
  -H "Content-Type: application/json" `
  -d "{\"username\":\"demo\",\"password\":\"demo123456\"}"
```

上传文档：

```powershell
curl -X POST http://localhost:8081/api/documents `
  -H "Content-Type: application/json" `
  -H "Authorization: Bearer <token>" `
  -d "{\"title\":\"Java 面试笔记\",\"originalName\":\"note.txt\",\"content\":\"Redis 可以用于热点缓存、验证码缓存、接口限流。MySQL 索引可以提升查询效率，但要考虑回表和写入成本。\"}"
```

知识库问答：

```powershell
curl -X POST http://localhost:8081/api/ask `
  -H "Content-Type: application/json" `
  -H "Authorization: Bearer <token>" `
  -d "{\"question\":\"Redis 在项目里能解决什么问题？\",\"topK\":3}"
```

## 可以写进简历的亮点

- 实现用户登录鉴权、文档管理、文本分段、向量化、相似度检索、RAG 问答和历史记录。
- 提供可视化前端页面，支持文档上传、知识库问答、引用片段、文档列表和问答历史展示。
- 默认使用本地轻量向量检索，便于演示；工程结构支持替换为 Qdrant / Milvus 等向量数据库。
- 可继续接入 Redis 做接口限流、热点问答缓存；接入 MySQL 支持生产化数据存储。
