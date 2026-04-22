package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
public class ChatService {

    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);

    @Value("${ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    @Value("${ollama.chat.model:qwen2.5-coder:7b}")
    private String chatModel;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 构建系统提示词（包含历史消息）
     */
    public String buildSystemPrompt(List<Map<String, String>> history) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个专业的智能助手，可以回答用户的问题。");
        if (!history.isEmpty()) {
            sb.append("\n\n--- 对话历史 ---\n");
            for (Map<String, String> msg : history) {
                if ("user".equals(msg.get("role"))) {
                    sb.append("用户: ").append(msg.get("content")).append("\n");
                } else if ("assistant".equals(msg.get("role"))) {
                    sb.append("助手: ").append(msg.get("content")).append("\n");
                }
            }
            sb.append("--- 对话历史结束 ---\n");
        }
        return sb.toString();
    }

    /**
     * 执行对话（非流式），返回完整回答
     */
    public String executeChat(String systemPrompt, String userQuestion) {
        try {
            logger.info("开始本地 Ollama 对话");
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemPrompt));
            messages.add(Map.of("role", "user", "content", userQuestion));

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", chatModel);
            requestBody.put("messages", messages);
            requestBody.put("stream", false);
            requestBody.put("options", Map.of("temperature", 0.3));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            String response = restTemplate.postForObject(ollamaBaseUrl + "/api/chat", entity, String.class);
            JsonNode root = objectMapper.readTree(response);
            String answer = root.path("message").path("content").asText();

            logger.info("本地 Ollama 对话完成，答案长度: {}", answer.length());
            return answer;
        } catch (Exception e) {
            logger.error("Ollama 调用失败", e);
            return "抱歉，我暂时无法回答。请稍后再试。";
        }
    }

    // 注：流式对话暂未实现，ChatController 中的流式接口已改为模拟流式输出
}