package edu.mcw.rgdai.controller;

import edu.mcw.rgdai.model.Answer;
import edu.mcw.rgdai.model.Question;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/chat-openai")
public class ChatControllerOpenAI {
    private static final Logger LOG = LoggerFactory.getLogger(ChatControllerOpenAI.class);
    private final ChatClient chatClient;

    public ChatControllerOpenAI(ApplicationContext context,
                                @Qualifier("openaiVectorStore") VectorStore openaiVectorStore) {
        LOG.info("🤖 Initializing OpenAI ChatController with dedicated OpenAI vector store");

        // Debug: Print all available ChatModel beans
        Map<String, ChatModel> chatModels = context.getBeansOfType(ChatModel.class);
        LOG.info("Available ChatModel beans:");
        for (Map.Entry<String, ChatModel> entry : chatModels.entrySet()) {
            LOG.info("Bean name: '{}', Class: {}", entry.getKey(), entry.getValue().getClass().getSimpleName());
        }

        // Find OpenAI chat model
        ChatModel openAiChatModel = null;
        for (Map.Entry<String, ChatModel> entry : chatModels.entrySet()) {
            if (entry.getValue().getClass().getSimpleName().toLowerCase().contains("openai")) {
                openAiChatModel = entry.getValue();
                LOG.info("✅ Found OpenAI ChatModel: {}", entry.getKey());
                break;
            }
        }

        if (openAiChatModel == null) {
            throw new RuntimeException("❌ OpenAI ChatModel not found! Available models: " + chatModels.keySet());
        }

        this.chatClient = ChatClient.builder(openAiChatModel)
                .defaultAdvisors(
                        new SimpleLoggerAdvisor(),
                        new QuestionAnswerAdvisor(openaiVectorStore, SearchRequest.defaults()
                                .withTopK(8)
                                .withSimilarityThreshold(0.38))
                )
                .build();
        LOG.info("✅ OpenAI ChatClient initialized successfully with dedicated OpenAI vector store");
    }

    @PostMapping
    public Answer chat(@RequestBody Question question, Authentication user) {
        LOG.info("🔵 OpenAI - Received question: {}", question.getQuestion());

        // Handle simple greetings
        if (isGreeting(question.getQuestion())) {
            return new Answer("Hello! I'm the OpenAI version. I can help you with questions about the documents in my knowledge base. What would you like to know?");
        }

        try {
            Answer answer = chatClient.prompt()
                    .user(question.getQuestion())
                    .call()
                    .entity(Answer.class);

            LOG.info("🔵 OpenAI - Generated answer: {}", answer.getAnswer());
            return answer;
        } catch (Exception e) {
            LOG.error("❌ OpenAI - Error generating response", e);
            return new Answer("OpenAI Error: " + e.getMessage() + ". Your Ollama implementation is still working at /chat endpoint.");
        }
    }

    private boolean isGreeting(String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        return text.toLowerCase().matches(".*\\b(hi|hello|hey|greetings)\\b.*");
    }
}