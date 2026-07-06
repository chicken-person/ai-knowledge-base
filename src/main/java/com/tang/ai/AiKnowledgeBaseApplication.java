package com.tang.ai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

@SpringBootApplication
public class AiKnowledgeBaseApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiKnowledgeBaseApplication.class, args);
    }

    @Bean
    CommandLineRunner seedDemo(AuthService authService, DocumentService documentService) {
        return args -> {
            UserView demo = authService.findByUsername("demo");
            if (demo == null) {
                demo = authService.register("demo", "demo123456");
            }
            if (documentService.countDocuments(demo.id()) == 0) {
                documentService.createDocument(
                        demo.id(),
                        new DocumentRequest(
                                "Java 后端面试速记",
                                "java-backend-notes.txt",
                                """
                                MySQL 索引适合提升查询效率，但需要结合区分度、回表成本和写入开销综合判断。
                                Redis 可以承担热点数据缓存、验证码缓存、接口限流和分布式锁等场景。
                                RAG 的核心流程是文档解析、文本分段、向量化、相似度检索、上下文拼接和大模型生成。
                                Java 后端实习面试经常考察集合、线程池、JVM 基础、Spring IOC/AOP、HTTP/TCP 和项目设计。
                                Docker Compose 可以把数据库、缓存、向量数据库和后端服务编排起来，方便本地演示。
                                """
                        )
                );
            }
        };
    }

    record ApiResponse<T>(boolean success, String message, T data) {
        static <T> ApiResponse<T> ok(T data) {
            return new ApiResponse<>(true, "ok", data);
        }

        static <T> ApiResponse<T> fail(String message) {
            return new ApiResponse<>(false, message, null);
        }
    }

    record AuthRequest(
            @NotBlank @Size(min = 3, max = 32) String username,
            @NotBlank @Size(min = 6, max = 64) String password
    ) {
    }

    record LoginResponse(String token, UserView user) {
    }

    record UserView(Long id, String username) {
    }

    record DocumentRequest(
            @NotBlank @Size(max = 160) String title,
            String originalName,
            @NotBlank @Size(min = 10, max = 20000) String content
    ) {
    }

    record DocumentView(Long id, String title, String originalName, Integer chunkCount, String createdAt) {
    }

    record AskRequest(@NotBlank String question, Integer topK) {
    }

    record ChunkHit(String title, String content, double score) {
    }

    record AskResponse(String question, String answer, List<ChunkHit> references) {
    }

    record ConversationView(Long id, String question, String answer, String createdAt) {
    }

    @RestController
    @RequestMapping("/api")
    static class AuthController {
        private final AuthService authService;
        private final TokenService tokenService;

        AuthController(AuthService authService, TokenService tokenService) {
            this.authService = authService;
            this.tokenService = tokenService;
        }

        @PostMapping("/auth/register")
        ResponseEntity<ApiResponse<LoginResponse>> register(@Valid @RequestBody AuthRequest request) {
            try {
                UserView user = authService.register(request.username(), request.password());
                return ResponseEntity.ok(ApiResponse.ok(new LoginResponse(tokenService.issue(user.id()), user)));
            } catch (DuplicateKeyException ex) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.fail("username already exists"));
            }
        }

        @PostMapping("/auth/login")
        ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody AuthRequest request) {
            UserView user = authService.login(request.username(), request.password());
            return ResponseEntity.ok(ApiResponse.ok(new LoginResponse(tokenService.issue(user.id()), user)));
        }

        @GetMapping("/health")
        ApiResponse<Map<String, Object>> health() {
            return ApiResponse.ok(Map.of("service", "ai-knowledge-base", "status", "UP"));
        }
    }

    @RestController
    @RequestMapping("/api")
    static class DocumentController {
        private final TokenService tokenService;
        private final DocumentService documentService;

        DocumentController(TokenService tokenService, DocumentService documentService) {
            this.tokenService = tokenService;
            this.documentService = documentService;
        }

        @PostMapping("/documents")
        ApiResponse<DocumentView> createDocument(
                @RequestHeader(value = "Authorization", required = false) String authorization,
                @Valid @RequestBody DocumentRequest request
        ) {
            long userId = tokenService.requireUser(authorization);
            return ApiResponse.ok(documentService.createDocument(userId, request));
        }

        @GetMapping("/documents")
        ApiResponse<List<DocumentView>> documents(@RequestHeader(value = "Authorization", required = false) String authorization) {
            long userId = tokenService.requireUser(authorization);
            return ApiResponse.ok(documentService.listDocuments(userId));
        }

        @PostMapping("/ask")
        ApiResponse<AskResponse> ask(
                @RequestHeader(value = "Authorization", required = false) String authorization,
                @Valid @RequestBody AskRequest request
        ) {
            long userId = tokenService.requireUser(authorization);
            return ApiResponse.ok(documentService.ask(userId, request));
        }

        @GetMapping("/conversations")
        ApiResponse<List<ConversationView>> conversations(@RequestHeader(value = "Authorization", required = false) String authorization) {
            long userId = tokenService.requireUser(authorization);
            return ApiResponse.ok(documentService.listConversations(userId));
        }
    }

    @Service
    static class AuthService {
        private final JdbcTemplate jdbcTemplate;
        private final SecureRandom secureRandom = new SecureRandom();

        AuthService(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        boolean exists(String username) {
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE username = ?", Integer.class, username);
            return count != null && count > 0;
        }

        UserView findByUsername(String username) {
            String normalized = username.trim().toLowerCase(Locale.ROOT);
            List<UserView> users = jdbcTemplate.query(
                    "SELECT id, username FROM users WHERE username = ?",
                    (rs, rowNum) -> new UserView(rs.getLong("id"), rs.getString("username")),
                    normalized
            );
            return users.isEmpty() ? null : users.get(0);
        }

        UserView register(String username, String password) {
            String normalized = username.trim().toLowerCase(Locale.ROOT);
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO users(username, password_hash) VALUES (?, ?)",
                        new String[]{"id"}
                );
                statement.setString(1, normalized);
                statement.setString(2, hashPassword(password));
                return statement;
            }, keyHolder);
            Number key = Objects.requireNonNull(keyHolder.getKey(), "generated user id");
            return new UserView(key.longValue(), normalized);
        }

        UserView login(String username, String password) {
            String normalized = username.trim().toLowerCase(Locale.ROOT);
            return jdbcTemplate.query(
                    "SELECT id, username, password_hash FROM users WHERE username = ?",
                    rs -> {
                        if (!rs.next()) {
                            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid username or password");
                        }
                        String stored = rs.getString("password_hash");
                        if (!matches(password, stored)) {
                            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid username or password");
                        }
                        return new UserView(rs.getLong("id"), rs.getString("username"));
                    },
                    normalized
            );
        }

        private String hashPassword(String password) {
            byte[] salt = new byte[16];
            secureRandom.nextBytes(salt);
            byte[] digest = sha256(concat(salt, password.getBytes(StandardCharsets.UTF_8)));
            return Base64.getEncoder().encodeToString(salt) + ":" + HexFormat.of().formatHex(digest);
        }

        private boolean matches(String password, String stored) {
            String[] parts = stored.split(":");
            if (parts.length != 2) {
                return false;
            }
            byte[] salt = Base64.getDecoder().decode(parts[0]);
            byte[] digest = sha256(concat(salt, password.getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(HexFormat.of().parseHex(parts[1]), digest);
        }

        private byte[] sha256(byte[] value) {
            try {
                return MessageDigest.getInstance("SHA-256").digest(value);
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }

        private byte[] concat(byte[] first, byte[] second) {
            byte[] all = new byte[first.length + second.length];
            System.arraycopy(first, 0, all, 0, first.length);
            System.arraycopy(second, 0, all, first.length, second.length);
            return all;
        }
    }

    @Service
    static class TokenService {
        private final byte[] secret;

        TokenService(@Value("${app.token-secret}") String secret) {
            this.secret = secret.getBytes(StandardCharsets.UTF_8);
        }

        String issue(long userId) {
            long expiresAt = Instant.now().plusSeconds(7 * 24 * 3600).getEpochSecond();
            String payload = userId + ":" + expiresAt;
            return base64(payload.getBytes(StandardCharsets.UTF_8)) + "." + base64(sign(payload));
        }

        long requireUser(String authorization) {
            if (authorization == null || !authorization.startsWith("Bearer ")) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing bearer token");
            }
            return parse(authorization.substring("Bearer ".length()));
        }

        private long parse(String token) {
            try {
                String[] parts = token.split("\\.");
                if (parts.length != 2) {
                    throw new IllegalArgumentException("bad token");
                }
                String payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
                byte[] expected = sign(payload);
                byte[] actual = Base64.getUrlDecoder().decode(parts[1]);
                if (!MessageDigest.isEqual(expected, actual)) {
                    throw new IllegalArgumentException("bad signature");
                }
                String[] values = payload.split(":");
                long expiresAt = Long.parseLong(values[1]);
                if (Instant.now().getEpochSecond() > expiresAt) {
                    throw new IllegalArgumentException("expired");
                }
                return Long.parseLong(values[0]);
            } catch (Exception ex) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid bearer token");
            }
        }

        private byte[] sign(String payload) {
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(secret, "HmacSHA256"));
                return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }

        private String base64(byte[] value) {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
        }
    }

    @Service
    static class DocumentService {
        private static final int VECTOR_SIZE = 128;

        private final JdbcTemplate jdbcTemplate;

        DocumentService(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        DocumentView createDocument(long userId, DocumentRequest request) {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO documents(user_id, title, original_name, content) VALUES (?, ?, ?, ?)",
                        new String[]{"id"}
                );
                statement.setLong(1, userId);
                statement.setString(2, request.title().trim());
                statement.setString(3, request.originalName());
                statement.setString(4, request.content());
                return statement;
            }, keyHolder);
            long documentId = Objects.requireNonNull(keyHolder.getKey()).longValue();
            List<String> chunks = chunk(request.content(), 480);
            for (int i = 0; i < chunks.size(); i++) {
                jdbcTemplate.update(
                        "INSERT INTO document_chunks(document_id, chunk_index, content, embedding) VALUES (?, ?, ?, ?)",
                        documentId,
                        i,
                        chunks.get(i),
                        serialize(embed(chunks.get(i)))
                );
            }
            return new DocumentView(documentId, request.title(), request.originalName(), chunks.size(), Instant.now().toString());
        }

        int countDocuments(long userId) {
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM documents WHERE user_id = ?", Integer.class, userId);
            return count == null ? 0 : count;
        }

        List<DocumentView> listDocuments(long userId) {
            return jdbcTemplate.query(
                    """
                    SELECT d.id, d.title, d.original_name, d.created_at, COUNT(c.id) AS chunk_count
                    FROM documents d
                    LEFT JOIN document_chunks c ON c.document_id = d.id
                    WHERE d.user_id = ?
                    GROUP BY d.id, d.title, d.original_name, d.created_at
                    ORDER BY d.id DESC
                    """,
                    (rs, rowNum) -> new DocumentView(
                            rs.getLong("id"),
                            rs.getString("title"),
                            rs.getString("original_name"),
                            rs.getInt("chunk_count"),
                            rs.getTimestamp("created_at").toInstant().toString()
                    ),
                    userId
            );
        }

        AskResponse ask(long userId, AskRequest request) {
            int topK = request.topK() == null ? 3 : Math.max(1, Math.min(request.topK(), 5));
            double[] queryVector = embed(request.question());
            List<ChunkHit> hits = jdbcTemplate.query(
                            """
                            SELECT d.title, c.content, c.embedding
                            FROM document_chunks c
                            JOIN documents d ON d.id = c.document_id
                            WHERE d.user_id = ?
                            """,
                            (rs, rowNum) -> new ChunkHit(
                                    rs.getString("title"),
                                    rs.getString("content"),
                                    cosine(queryVector, deserialize(rs.getString("embedding")))
                            ),
                            userId
                    )
                    .stream()
                    .sorted(Comparator.comparingDouble(ChunkHit::score).reversed())
                    .limit(topK)
                    .toList();

            String answer = composeAnswer(request.question(), hits);
            jdbcTemplate.update(
                    "INSERT INTO conversations(user_id, question, answer) VALUES (?, ?, ?)",
                    userId,
                    request.question(),
                    answer
            );
            return new AskResponse(request.question(), answer, hits);
        }

        List<ConversationView> listConversations(long userId) {
            return jdbcTemplate.query(
                    "SELECT id, question, answer, created_at FROM conversations WHERE user_id = ? ORDER BY id DESC LIMIT 20",
                    (rs, rowNum) -> new ConversationView(
                            rs.getLong("id"),
                            rs.getString("question"),
                            rs.getString("answer"),
                            rs.getTimestamp("created_at").toInstant().toString()
                    ),
                    userId
            );
        }

        private String composeAnswer(String question, List<ChunkHit> hits) {
            if (hits.isEmpty()) {
                return "知识库中暂时没有可用内容。请先上传与问题相关的文档。";
            }
            StringBuilder answer = new StringBuilder();
            answer.append("问题：").append(question).append("\n\n");
            answer.append("基于知识库检索，建议重点关注：\n");
            for (int i = 0; i < hits.size(); i++) {
                ChunkHit hit = hits.get(i);
                answer.append(i + 1)
                        .append(". [")
                        .append(hit.title())
                        .append("] ")
                        .append(trim(hit.content(), 180))
                        .append("\n");
            }
            answer.append("\n本地演示版使用轻量向量检索和规则生成；接入真实大模型 API 后，可将以上片段作为上下文生成更自然的回答。");
            return answer.toString();
        }

        private List<String> chunk(String content, int maxLength) {
            List<String> result = new ArrayList<>();
            String normalized = content.replace("\r\n", "\n").trim();
            for (int start = 0; start < normalized.length(); start += maxLength) {
                result.add(normalized.substring(start, Math.min(start + maxLength, normalized.length())).trim());
            }
            return result;
        }

        private double[] embed(String text) {
            double[] vector = new double[VECTOR_SIZE];
            String[] tokens = text.toLowerCase(Locale.ROOT).split("[^\\p{IsHan}\\p{L}\\p{N}]+");
            for (String token : tokens) {
                if (token.isBlank()) {
                    continue;
                }
                int first = Math.floorMod(token.hashCode(), VECTOR_SIZE);
                int second = Math.floorMod(token.hashCode() * 31, VECTOR_SIZE);
                vector[first] += 1.0;
                vector[second] += 0.35;
            }
            double length = 0;
            for (double value : vector) {
                length += value * value;
            }
            length = Math.sqrt(length);
            if (length == 0) {
                return vector;
            }
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= length;
            }
            return vector;
        }

        private String serialize(double[] vector) {
            StringJoiner joiner = new StringJoiner(",");
            for (double value : vector) {
                joiner.add(String.format(Locale.US, "%.6f", value));
            }
            return joiner.toString();
        }

        private double[] deserialize(String value) {
            String[] parts = value.split(",");
            double[] vector = new double[parts.length];
            for (int i = 0; i < parts.length; i++) {
                vector[i] = Double.parseDouble(parts[i]);
            }
            return vector;
        }

        private double cosine(double[] left, double[] right) {
            double score = 0;
            for (int i = 0; i < Math.min(left.length, right.length); i++) {
                score += left[i] * right[i];
            }
            return score;
        }

        private String trim(String content, int maxLength) {
            String oneLine = content.replace("\n", " ").trim();
            if (oneLine.length() <= maxLength) {
                return oneLine;
            }
            return oneLine.substring(0, maxLength) + "...";
        }
    }
}
