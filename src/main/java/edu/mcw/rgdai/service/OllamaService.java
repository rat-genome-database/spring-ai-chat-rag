package edu.mcw.rgdai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Service
public class OllamaService {
    private static final Logger LOG = LoggerFactory.getLogger(OllamaService.class);
    private final String baseUrl;
    private final String model;
    private final RestTemplate restTemplate;

    public OllamaService(
            @Value("${spring.ai.ollama.base-url}") String baseUrl,
            @Value("${spring.ai.ollama.chat.model}") String model) {
        this.baseUrl = baseUrl;
        this.model = model;
        this.restTemplate = new RestTemplate();
    }

    public String generateResponse(String prompt) {
        LOG.info("Generating response for prompt: {}", prompt);

        String url = baseUrl + "/api/generate";
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("prompt", prompt);
        requestBody.put("stream", false); // Important: disable streaming for simpler response handling
        requestBody.put("options", Map.of("temperature", 0.0));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

        try {
            Map<String, Object> response = restTemplate.postForObject(url, request, Map.class);
            String generatedText = (String) response.get("response");
            LOG.info("Generated response: {}", generatedText);
            return generatedText;
        } catch (Exception e) {
            LOG.error("Error generating response", e);
            return "Error: " + e.getMessage();
        }
    }
}
