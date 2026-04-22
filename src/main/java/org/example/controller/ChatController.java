package org.example.controller;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;
import org.example.service.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 统一 API 控制器
 * 适配前端接口需求（已完全本地化，无 Spring AI 依赖）
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

    @Autowired
    private ChatService chatService;

    @Value("${agent.python.url:http://localhost:5000}")
    private String pythonAgentUrl;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    // 存储会话信息
    private final Map<String, SessionInfo> sessions = new ConcurrentHashMap<>();

    // 最大历史消息窗口大小（成对计算：用户消息+AI回复=1对）
    private static final int MAX_WINDOW_SIZE = 6;

    @GetMapping("/trigger-500")
    public ResponseEntity<String> trigger500() {
        throw new RuntimeException("Simulated 500 error for alert testing");
    }

    /**
     * 普通对话接口 - 使用本地模型（纯文本，无工具调用）
     */
    @PostMapping("/chat")
    public ResponseEntity<ApiResponse<ChatResponse>> chat(@RequestBody ChatRequest request) {
        try {
            logger.info("转发对话请求至 Python Agent - SessionId: {}, Question: {}", request.getId(), request.getQuestion());

            SessionInfo session = getOrCreateSession(request.getId());
            List<Map<String, String>> history = session.getHistory();

            // 构建转发请求体
            Map<String, Object> pythonRequest = new HashMap<>();
            pythonRequest.put("question", request.getQuestion());
            pythonRequest.put("history", history);

            // 调用 Python /chat 接口
            RestTemplate restTemplate = new RestTemplate();
            String pythonUrl = pythonAgentUrl + "/chat";
            ResponseEntity<Map> response = restTemplate.postForEntity(pythonUrl, pythonRequest, Map.class);

            String answer = (String) response.getBody().get("answer");

            session.addMessage(request.getQuestion(), answer);
            logger.info("已更新会话历史 - SessionId: {}, 当前消息对数: {}",
                    request.getId(), session.getMessagePairCount());

            return ResponseEntity.ok(ApiResponse.success(ChatResponse.success(answer)));

        } catch (Exception e) {
            logger.error("对话失败", e);
            return ResponseEntity.ok(ApiResponse.success(ChatResponse.error(e.getMessage())));
        }
    }

    /**
     * 清空会话历史
     */
    @PostMapping("/chat/clear")
    public ResponseEntity<ApiResponse<String>> clearChatHistory(@RequestBody ClearRequest request) {
        try {
            logger.info("收到清空会话历史请求 - SessionId: {}", request.getId());

            if (request.getId() == null || request.getId().isEmpty()) {
                return ResponseEntity.ok(ApiResponse.error("会话ID不能为空"));
            }

            SessionInfo session = sessions.get(request.getId());
            if (session != null) {
                session.clearHistory();
                return ResponseEntity.ok(ApiResponse.success("会话历史已清空"));
            } else {
                return ResponseEntity.ok(ApiResponse.error("会话不存在"));
            }

        } catch (Exception e) {
            logger.error("清空会话历史失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * 流式对话接口（SSE）- 使用本地模型（模拟流式输出）
     */
    @PostMapping(value = "/chat_stream", produces = "text/event-stream;charset=UTF-8")
    public Flux<ServerSentEvent<String>> chatStream(@RequestBody ChatRequest request) {
        SessionInfo session = getOrCreateSession(request.getId());
        List<Map<String, String>> history = session.getHistory();

        Map<String, Object> pythonRequest = new HashMap<>();
        pythonRequest.put("question", request.getQuestion());
        pythonRequest.put("history", history);

        WebClient webClient = WebClient.builder().baseUrl(pythonAgentUrl).build();
        StringBuilder fullAnswerBuilder = new StringBuilder();

        return webClient.post()
                .uri("/chat_stream")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(pythonRequest)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .doOnNext(event -> {
                    // 累积答案（简单拼接 content 类型的数据）
                    if (event.data() != null) {
                        try {
                            JsonNode node = new ObjectMapper().readTree(event.data());
                            if ("content".equals(node.path("type").asText())) {
                                fullAnswerBuilder.append(node.path("data").asText());
                            }
                        } catch (Exception ignored) {}
                    }
                })
                .doOnComplete(() -> {
                    session.addMessage(request.getQuestion(), fullAnswerBuilder.toString());
                    logger.info("流式对话完成 - SessionId: {}, 答案长度: {}",
                            request.getId(), fullAnswerBuilder.length());
                })
                .onErrorResume(e -> {
                    logger.error("转发 Python 流式对话失败", e);
                    return Flux.just(ServerSentEvent.<String>builder()
                            .data("{\"type\":\"error\",\"data\":\"对话服务暂时不可用\"}")
                            .build());
                });
    }

    /**
     * AI 智能运维接口（SSE 流式模式）- 转发至 Python Agent 服务
     */
    @PostMapping(value = "/ai_ops", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> aiOps() {
        WebClient webClient = WebClient.builder()
                .baseUrl(pythonAgentUrl)
                .build();

        return webClient.post()
                .uri("/ai_ops/stream")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .doOnNext(event -> logger.debug("转发 SSE 事件: {}", event.data()))
                .onErrorResume(e -> {
                    logger.error("转发 Python Agent SSE 失败", e);
                    return Flux.just(ServerSentEvent.<String>builder()
                            .data("{\"type\":\"error\",\"data\":\"AI Ops 服务暂时不可用\"}")
                            .build());
                });
    }

    /**
     * 获取会话信息
     */
    @GetMapping("/chat/session/{sessionId}")
    public ResponseEntity<ApiResponse<SessionInfoResponse>> getSessionInfo(@PathVariable String sessionId) {
        try {
            logger.info("收到获取会话信息请求 - SessionId: {}", sessionId);

            SessionInfo session = sessions.get(sessionId);
            if (session != null) {
                SessionInfoResponse response = new SessionInfoResponse();
                response.setSessionId(sessionId);
                response.setMessagePairCount(session.getMessagePairCount());
                response.setCreateTime(session.createTime);
                return ResponseEntity.ok(ApiResponse.success(response));
            } else {
                return ResponseEntity.ok(ApiResponse.error("会话不存在"));
            }

        } catch (Exception e) {
            logger.error("获取会话信息失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    // ==================== 辅助方法 ====================

    private SessionInfo getOrCreateSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = UUID.randomUUID().toString();
        }
        return sessions.computeIfAbsent(sessionId, SessionInfo::new);
    }

    // ==================== 内部类 ====================

    private static class SessionInfo {
        private final String sessionId;
        private final List<Map<String, String>> messageHistory;
        private final long createTime;
        private final ReentrantLock lock;

        public SessionInfo(String sessionId) {
            this.sessionId = sessionId;
            this.messageHistory = new ArrayList<>();
            this.createTime = System.currentTimeMillis();
            this.lock = new ReentrantLock();
        }

        public void addMessage(String userQuestion, String aiAnswer) {
            lock.lock();
            try {
                Map<String, String> userMsg = new HashMap<>();
                userMsg.put("role", "user");
                userMsg.put("content", userQuestion);
                messageHistory.add(userMsg);

                Map<String, String> assistantMsg = new HashMap<>();
                assistantMsg.put("role", "assistant");
                assistantMsg.put("content", aiAnswer);
                messageHistory.add(assistantMsg);

                int maxMessages = MAX_WINDOW_SIZE * 2;
                while (messageHistory.size() > maxMessages) {
                    messageHistory.remove(0);
                    if (!messageHistory.isEmpty()) {
                        messageHistory.remove(0);
                    }
                }
            } finally {
                lock.unlock();
            }
        }

        public List<Map<String, String>> getHistory() {
            lock.lock();
            try {
                return new ArrayList<>(messageHistory);
            } finally {
                lock.unlock();
            }
        }

        public void clearHistory() {
            lock.lock();
            try {
                messageHistory.clear();
                logger.info("会话 {} 历史消息已清空", sessionId);
            } finally {
                lock.unlock();
            }
        }

        public int getMessagePairCount() {
            lock.lock();
            try {
                return messageHistory.size() / 2;
            } finally {
                lock.unlock();
            }
        }
    }

    @Setter
    @Getter
    public static class ChatRequest {
        @JsonProperty(value = "Id")
        @JsonAlias({"id", "ID"})
        private String Id;

        @JsonProperty(value = "Question")
        @JsonAlias({"question", "QUESTION"})
        private String Question;
    }

    @Setter
    @Getter
    public static class ClearRequest {
        @JsonProperty(value = "Id")
        @JsonAlias({"id", "ID"})
        private String Id;
    }

    @Setter
    @Getter
    public static class SessionInfoResponse {
        private String sessionId;
        private int messagePairCount;
        private long createTime;
    }

    @Setter
    @Getter
    public static class ChatResponse {
        private boolean success;
        private String answer;
        private String errorMessage;

        public static ChatResponse success(String answer) {
            ChatResponse response = new ChatResponse();
            response.setSuccess(true);
            response.setAnswer(answer);
            return response;
        }

        public static ChatResponse error(String errorMessage) {
            ChatResponse response = new ChatResponse();
            response.setSuccess(false);
            response.setErrorMessage(errorMessage);
            return response;
        }
    }

    @Setter
    @Getter
    public static class SseMessage {
        private String type;
        private String data;

        public static SseMessage content(String data) {
            SseMessage message = new SseMessage();
            message.setType("content");
            message.setData(data);
            return message;
        }

        public static SseMessage error(String errorMessage) {
            SseMessage message = new SseMessage();
            message.setType("error");
            message.setData(errorMessage);
            return message;
        }

        public static SseMessage done() {
            SseMessage message = new SseMessage();
            message.setType("done");
            message.setData(null);
            return message;
        }
    }

    @Getter
    @Setter
    public static class ApiResponse<T> {
        private int code;
        private String message;
        private T data;

        public static <T> ApiResponse<T> success(T data) {
            ApiResponse<T> response = new ApiResponse<>();
            response.setCode(200);
            response.setMessage("success");
            response.setData(data);
            return response;
        }

        public static <T> ApiResponse<T> error(String message) {
            ApiResponse<T> response = new ApiResponse<>();
            response.setCode(500);
            response.setMessage(message);
            return response;
        }
    }
}