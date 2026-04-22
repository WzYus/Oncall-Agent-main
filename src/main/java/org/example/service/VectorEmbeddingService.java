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

import jakarta.annotation.PostConstruct;
import java.util.*;

@Service
public class VectorEmbeddingService {

    private static final Logger logger = LoggerFactory.getLogger(VectorEmbeddingService.class);

    @Value("${spring.ai.ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    @Value("${spring.ai.ollama.embedding.model:nomic-embed-text}")
    private String embeddingModel;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() {
        logger.info("本地 Ollama Embedding 服务初始化完成，模型: {}, 地址: {}", embeddingModel, ollamaBaseUrl);
    }

    /**
     * 生成向量嵌入（单文本）
     */
    public List<Float> generateEmbedding(String content) {
        try {
            if (content == null || content.trim().isEmpty()) {
                throw new IllegalArgumentException("内容不能为空");
            }

            logger.debug("开始生成向量嵌入, 内容长度: {} 字符", content.length());

            // 构建 Ollama Embedding API 请求体
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", embeddingModel);
            requestBody.put("input", Collections.singletonList(content));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            // 调用 Ollama API
            String response = restTemplate.postForObject(ollamaBaseUrl + "/api/embed", entity, String.class);
            JsonNode root = objectMapper.readTree(response);
            JsonNode embeddings = root.path("embeddings");
            if (embeddings.isEmpty()) {
                throw new RuntimeException("Ollama API 返回空向量");
            }

            JsonNode firstEmbedding = embeddings.get(0);
            List<Float> floatEmbedding = new ArrayList<>(firstEmbedding.size());
            for (JsonNode valueNode : firstEmbedding) {
                floatEmbedding.add(valueNode.floatValue());
            }

            logger.info("成功生成向量嵌入, 内容长度: {} 字符, 向量维度: {}", content.length(), floatEmbedding.size());
            return floatEmbedding;

        } catch (Exception e) {
            logger.error("生成向量嵌入失败", e);
            throw new RuntimeException("生成向量嵌入失败: " + e.getMessage(), e);
        }
    }

    /**
     * 批量生成向量嵌入
     */
    public List<List<Float>> generateEmbeddings(List<String> contents) {
        try {
            if (contents == null || contents.isEmpty()) {
                return Collections.emptyList();
            }

            logger.info("开始批量生成向量嵌入, 数量: {}", contents.size());

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", embeddingModel);
            requestBody.put("input", contents);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            String response = restTemplate.postForObject(ollamaBaseUrl + "/api/embed", entity, String.class);
            JsonNode root = objectMapper.readTree(response);
            JsonNode embeddings = root.path("embeddings");

            List<List<Float>> result = new ArrayList<>();
            for (JsonNode emb : embeddings) {
                List<Float> floatEmb = new ArrayList<>(emb.size());
                for (JsonNode val : emb) {
                    floatEmb.add(val.floatValue());
                }
                result.add(floatEmb);
            }

            logger.info("成功批量生成向量嵌入, 数量: {}, 维度: {}", result.size(), result.isEmpty() ? 0 : result.get(0).size());
            return result;

        } catch (Exception e) {
            logger.error("批量生成向量嵌入失败", e);
            throw new RuntimeException("批量生成向量嵌入失败: " + e.getMessage(), e);
        }
    }

    public List<Float> generateQueryVector(String query) {
        return generateEmbedding(query);
    }

    public float calculateCosineSimilarity(List<Float> vector1, List<Float> vector2) {
        if (vector1.size() != vector2.size()) {
            throw new IllegalArgumentException("向量维度不匹配");
        }
        float dotProduct = 0.0f, norm1 = 0.0f, norm2 = 0.0f;
        for (int i = 0; i < vector1.size(); i++) {
            dotProduct += vector1.get(i) * vector2.get(i);
            norm1 += vector1.get(i) * vector1.get(i);
            norm2 += vector2.get(i) * vector2.get(i);
        }
        return dotProduct / (float) (Math.sqrt(norm1) * Math.sqrt(norm2));
    }
}