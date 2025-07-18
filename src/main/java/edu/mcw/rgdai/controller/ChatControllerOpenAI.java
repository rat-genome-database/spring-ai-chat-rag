package edu.mcw.rgdai.controller;

import edu.mcw.rgdai.model.Answer;
import edu.mcw.rgdai.model.Question;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.InMemoryChatMemory;
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

    private static final String CHAT_MEMORY_CONVERSATION_ID_KEY = "conversationId";

    public ChatControllerOpenAI(ApplicationContext context,
                                @Qualifier("openaiVectorStore") VectorStore openaiVectorStore) {
        LOG.info("🤖 Initializing OpenAI ChatController with system messages for doc context");
        this.openaiVectorStore = openaiVectorStore;

        // Find OpenAI chat model
        Map<String, ChatModel> chatModels = context.getBeansOfType(ChatModel.class);
        ChatModel openAiChatModel = null;
        for (Map.Entry<String, ChatModel> entry : chatModels.entrySet()) {
            if (entry.getValue().getClass().getSimpleName().toLowerCase().contains("openai")) {
                openAiChatModel = entry.getValue();
                LOG.info("✅ Found OpenAI ChatModel: {}", entry.getKey());
                break;
            }
        }

        if (openAiChatModel == null) {
            throw new RuntimeException("❌ OpenAI ChatModel not found!");
        }

        this.chatClient = ChatClient.builder(openAiChatModel)
                .defaultAdvisors(
                        new SimpleLoggerAdvisor(),
                        new MessageChatMemoryAdvisor(new InMemoryChatMemory())
                )
                .build();

        LOG.info("✅ OpenAI ChatClient initialized with system message approach");
    }

    @PostMapping
    public Answer chat(@RequestBody Question question, Authentication user, HttpServletRequest request) {
        LOG.info("🔵 OpenAI - Received question: {}", question.getQuestion());
        String conversationId = (user != null) ? user.getName() : request.getSession().getId();

        // Handle simple greetings
        if (isGreeting(question.getQuestion())) {
            return new Answer("Hello! I'm the OpenAI version. I can help you with questions about the documents in my knowledge base. What would you like to know?");
        }

        try {
            // Get documents (same as before)
            List<Document> documents = openaiVectorStore.similaritySearch(
                    SearchRequest.query(question.getQuestion())
                            .withTopK(8)
                            .withSimilarityThreshold(0.38));

            LOG.info("🔵 OpenAI - Retrieved {} documents from vector store", documents.size());

            if (documents.isEmpty()) {
                return new Answer("I don't have information about that topic in my knowledge base.");
            }

            // Build doc context
            StringBuilder contextBuilder = new StringBuilder();
            for (Document doc : documents) {
                contextBuilder.append(doc.getContent()).append("\n\n");
            }

            String systemMessage = String.format("""
    You are a helpful AI assistant that answers questions based on the provided document context AND conversation history.

    IMPORTANT: You can use information from EITHER:
    1. The document context below
    2. The conversation history (previous messages in this chat)
    
    Context information is below, surrounded by ---------------------
    
    ---------------------
    %s
    ---------------------
    
    INSTRUCTIONS:
    - If the answer is in the conversation history, use that information
    - If the answer is in the document context, use that information  
    - If the answer is neither in conversation history nor document context, then say you can't answer
    - At the end of your response, add "SOURCES_USED:" followed by the filenames you actually used from these documents: %s
    """,
                    contextBuilder.toString(),
                    documents.stream()
                            .map(doc -> doc.getMetadata().getOrDefault("filename", "unknown").toString())
                            .distinct()
                            .reduce((a, b) -> a + ", " + b)
                            .orElse("none"));

            // Send clean user question + system context
            String response = chatClient.prompt()
                    .system(systemMessage)  // Document context goes in system message
                    .user(question.getQuestion())  // Only clean question in user message
                    .advisors(advisorSpec -> advisorSpec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, conversationId))
                    .call()
                    .content();

            LOG.info("🔵 OpenAI - Generated response with system message approach");
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