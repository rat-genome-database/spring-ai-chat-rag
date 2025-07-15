package edu.mcw.rgdai.controller;

import edu.mcw.rgdai.model.Answer;
import edu.mcw.rgdai.model.Question;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.ai.document.Document;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/chat-openai")
public class ChatControllerOpenAI {
    private static final Logger LOG = LoggerFactory.getLogger(ChatControllerOpenAI.class);
    private final ChatClient chatClient;
    private final VectorStore openaiVectorStore;

    public ChatControllerOpenAI(ApplicationContext context,
                                @Qualifier("openaiVectorStore") VectorStore openaiVectorStore) {
        LOG.info("🤖 Initializing OpenAI ChatController with Spring AI's exact prompt");
        this.openaiVectorStore = openaiVectorStore;

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

        // Build ChatClient without QuestionAnswerAdvisor - manual RAG
        this.chatClient = ChatClient.builder(openAiChatModel)
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();

        LOG.info("✅ OpenAI ChatClient initialized with Spring AI's exact prompt template");
    }

    @PostMapping
    public Answer chat(@RequestBody Question question, Authentication user) {
        LOG.info("🔵 OpenAI - Received question: {}", question.getQuestion());

        // Handle simple greetings
        if (isGreeting(question.getQuestion())) {
            return new Answer("Hello! I'm the OpenAI version. I can help you with questions about the documents in my knowledge base. What would you like to know?");
        }

        try {
            // Get documents (same as QuestionAnswerAdvisor)
            List<Document> documents = openaiVectorStore.similaritySearch(
                    SearchRequest.query(question.getQuestion())
                            .withTopK(8)
                            .withSimilarityThreshold(0.38));

            LOG.info("🔵 OpenAI - Retrieved {} documents from vector store", documents.size());

            if (documents.isEmpty()) {
                return new Answer("I don't have information about that topic in my knowledge base.");
            }

            // Build context exactly like Spring AI (just concatenate content)
            StringBuilder contextBuilder = new StringBuilder();
            for (Document doc : documents) {
                contextBuilder.append(doc.getContent()).append("\n\n");
            }

            // Use Spring AI's EXACT prompt template + source tracking
            String prompt = String.format("""
                %s
                
                Context information is below, surrounded by ---------------------
                
                ---------------------
                %s
                ---------------------
                
                Given the context and provided history information and not prior knowledge,
                reply to the user comment. If the answer is not in the context, inform
                the user that you can't answer the question.
                
                At the end of your response, add "SOURCES_USED:" followed by the filenames you actually used from these documents: %s
                """,
                    question.getQuestion(),
                    contextBuilder.toString(),
                    documents.stream()
                            .map(doc -> doc.getMetadata().getOrDefault("filename", "unknown").toString())
                            .distinct()
                            .reduce((a, b) -> a + ", " + b)
                            .orElse("none"));

            // Single API call
            String response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            LOG.info("🔵 OpenAI - Generated response with Spring AI prompt");
            return new Answer(response);

        } catch (Exception e) {
            LOG.error("❌ OpenAI - Error generating response", e);
            return new Answer("OpenAI Error: " + e.getMessage());
        }
    }

    private boolean isGreeting(String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        return text.toLowerCase().matches(".*\\b(hi|hello|hey|greetings)\\b.*");
    }
}