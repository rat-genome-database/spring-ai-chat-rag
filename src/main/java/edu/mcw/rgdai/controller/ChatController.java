//package edu.mcw.rgdai.controller;
//
//import edu.mcw.rgdai.model.Answer;
//import edu.mcw.rgdai.model.Question;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.ai.chat.client.ChatClient;
//import org.springframework.ai.chat.client.advisor.PromptChatMemoryAdvisor;
//import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor;
//import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
//import org.springframework.ai.chat.memory.InMemoryChatMemory;
//import org.springframework.ai.vectorstore.SearchRequest;
//import org.springframework.ai.vectorstore.VectorStore;
//import org.springframework.security.core.Authentication;
//import org.springframework.web.bind.annotation.*;
//@RestController
//@RequestMapping("/chat")
//public class ChatController {
//    private static final Logger LOG = LoggerFactory.getLogger(ChatController.class);
//    private final ChatClient chatClient;
//    private static final String CHAT_MEMORY_CONVERSATION_ID_KEY = "conversationId";
//
//    public ChatController(ChatClient.Builder chatClientBuilder, VectorStore vectorStore) {
//        LOG.info("Initializing ChatController");
//        this.chatClient = chatClientBuilder
//                .defaultAdvisors(
//                        new SimpleLoggerAdvisor(),
//                        new QuestionAnswerAdvisor(vectorStore, SearchRequest.defaults().withTopK(5)
//                                .withSimilarityThreshold(0.5)), // Lower threshold),  // Get top 5 matches
//                        new PromptChatMemoryAdvisor(new InMemoryChatMemory())
//                )
//                .build();
//        LOG.info("ChatClient initialized successfully");
//    }
//
//    @PostMapping
//    public Answer chat(@RequestBody Question question, Authentication user) {
//        LOG.info("Received question: {}", question.getQuestion());
//        Answer answer = chatClient.prompt()
//                .user(question.getQuestion())
//                .advisors(
//                        advisorSpec -> advisorSpec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, user.getPrincipal())
//                )
//                .call()
//                .entity(Answer.class);
//        LOG.info("Generated answer: {}", answer.getAnswer());
//        return answer;
//    }
//}
package edu.mcw.rgdai.controller;

import edu.mcw.rgdai.model.Answer;
import edu.mcw.rgdai.model.Question;
import edu.mcw.rgdai.service.OllamaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/chat")
public class ChatController {
    private static final Logger LOG = LoggerFactory.getLogger(ChatController.class);
    private final VectorStore vectorStore;
    private final OllamaService ollamaService;

    public ChatController(VectorStore vectorStore, OllamaService ollamaService) {
        this.vectorStore = vectorStore;
        this.ollamaService = ollamaService;
        LOG.info("ChatController initialized successfully");
    }

    @PostMapping
    public Answer chat(@RequestBody Question question, Authentication user) {
        LOG.info("Received question: {}", question.getQuestion());

        // 1. Retrieve relevant documents
        List<Document> documents = vectorStore.similaritySearch(
                SearchRequest.query(question.getQuestion()).withTopK(3)
        );

        // 2. Format the context
        String context = documents.stream()
                .map(Document::getContent)
                .collect(Collectors.joining("\n\n"));

        LOG.info("Retrieved context: {} characters", context.length());

        // 3. Create prompt with context
        String prompt = String.format("""
            Answer the following question based on this context:

            Context:
            %s

            Question: %s

            Answer:
            """, context, question.getQuestion());

        String prompt1=String.format("""
      You are an AI assistant that ONLY answers questions based on the provided context information.
    
    IMPORTANT INSTRUCTIONS:
    - If the answer is not contained in the context except for greeting messages, respond with ONLY: "I don't have information about that in my knowledge base."
    - Do not use any knowledge outside of the provided context
    - Do not make up or infer information not explicitly stated in the context
    
    Context:
    %s
    
    Question: %s
    
    Answer (based ONLY on the context above):
    """, context, question.getQuestion());

        String enhancedPrompt = String.format("""
        You are an intelligent AI assistant with access to a curated knowledge base. Your role is to provide accurate, helpful, and comprehensive answers based on the provided context.

        ## CORE INSTRUCTIONS:
        1. **Primary Source**: Always prioritize information from the provided context
        2. **Accuracy**: Only state facts that are explicitly supported by the context
        3. **Clarity**: Provide clear, well-structured responses that directly address the user's question
        4. **Completeness**: Give comprehensive answers when the context supports it
        5. **Transparency**: Clearly indicate when information is limited or unavailable

        ## RESPONSE GUIDELINES:
        - **When context is sufficient**: Provide a detailed, helpful answer citing relevant sources
        - **When context is partial**: Answer what you can and clearly state what information is missing
        - **When context is insufficient**: Respond with "I don't have enough information in my knowledge base to answer that question accurately."
        - **Always be conversational**: Write in a natural, helpful tone as if speaking to a colleague
        - **Structure your responses**: Use bullet points, numbered lists, or paragraphs as appropriate
        - **Cite sources when helpful**: Mention filenames or document sources when it adds credibility

        ## CONTEXT INFORMATION:
        %s

        ## USER QUESTION:
        %s

        ## YOUR RESPONSE:
        Based on the information in my knowledge base, here's what I can tell you:
        """,
                context.isEmpty() ? "No relevant documents found in the knowledge base." : context,
                question.getQuestion());

        // 4. Get response from Ollama
        String response = ollamaService.generateResponse(prompt1);

        return new Answer(response);
    }
}