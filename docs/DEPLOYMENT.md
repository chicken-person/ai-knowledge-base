# 部署说明

## 本地 jar

```powershell
mvn test
mvn -DskipTests package
java -jar target/ai-knowledge-base-0.1.0.jar
```

访问地址：

```text
http://localhost:8081/
```

## 环境变量

```powershell
$env:APP_TOKEN_SECRET="replace-with-a-long-random-secret"
$env:H2_CONSOLE_ENABLED="false"
```

## Docker

```powershell
mvn -DskipTests package
docker build -t ai-knowledge-base:0.1.0 .
docker run --rm -p 8081:8081 -e APP_TOKEN_SECRET=replace-me ai-knowledge-base:0.1.0
```

## Docker Compose

```powershell
mvn -DskipTests package
docker compose up --build
```
