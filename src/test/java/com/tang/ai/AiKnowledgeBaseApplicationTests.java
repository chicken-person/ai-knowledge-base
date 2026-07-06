package com.tang.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:ai_kb_test;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
                "app.token-secret=test-secret-for-ai-knowledge-base"
        }
)
class AiKnowledgeBaseApplicationTests {
    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void knowledgeBaseFlowSupportsDocumentLifecycleAndAsk() throws Exception {
        String username = "tester" + System.nanoTime();
        JsonNode register = post("/api/auth/register", Map.of("username", username, "password", "test123456"));
        String token = register.at("/data/token").asText();
        HttpHeaders headers = auth(token);

        JsonNode document = exchange(
                "/api/documents",
                HttpMethod.POST,
                headers,
                Map.of(
                        "title", "Redis 缓存治理方案",
                        "originalName", "../redis-plan.md",
                        "content", "Redis 在项目中用于热点数据缓存、验证码缓存、接口限流和分布式锁。缓存穿透通过布隆过滤器和空值缓存处理，缓存击穿通过互斥锁处理。"
                )
        );
        long documentId = document.at("/data/id").asLong();
        assertThat(document.at("/data/originalName").asText()).isEqualTo("redis-plan.md");

        JsonNode chunks = exchange("/api/documents/" + documentId + "/chunks", HttpMethod.GET, headers, null);
        assertThat(chunks.at("/data").size()).isGreaterThanOrEqualTo(1);

        JsonNode ask = exchange(
                "/api/ask",
                HttpMethod.POST,
                headers,
                Map.of("question", "Redis 能解决什么问题？", "topK", 2)
        );
        assertThat(ask.at("/data/answer").asText()).contains("Redis");
        assertThat(ask.at("/data/references").size()).isGreaterThanOrEqualTo(1);
        assertThat(ask.at("/data/confidence").asDouble()).isGreaterThanOrEqualTo(0);

        JsonNode dashboard = exchange("/api/dashboard", HttpMethod.GET, headers, null);
        assertThat(dashboard.at("/data/documentCount").asLong()).isEqualTo(1);
        assertThat(dashboard.at("/data/conversationCount").asLong()).isEqualTo(1);

        JsonNode reindex = exchange("/api/documents/" + documentId + "/reindex", HttpMethod.POST, headers, null);
        assertThat(reindex.at("/data/chunkCount").asInt()).isGreaterThanOrEqualTo(1);

        JsonNode delete = exchange("/api/documents/" + documentId, HttpMethod.DELETE, headers, null);
        assertThat(delete.at("/data/deleted").asBoolean()).isTrue();
    }

    private JsonNode post(String path, Object body) throws Exception {
        return exchange(path, HttpMethod.POST, new HttpHeaders(), body);
    }

    private JsonNode exchange(String path, HttpMethod method, HttpHeaders headers, Object body) throws Exception {
        headers.set("Content-Type", "application/json");
        ResponseEntity<String> response = restTemplate.exchange(path, method, new HttpEntity<>(body, headers), String.class);
        JsonNode json = objectMapper.readTree(response.getBody());
        assertThat(response.getStatusCode().is2xxSuccessful()).as(json.toString()).isTrue();
        assertThat(json.at("/success").asBoolean()).as(json.toString()).isTrue();
        return json;
    }

    private HttpHeaders auth(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
