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
                SearchRequest.query(question.getQuestion()).withTopK((int) 0.5)
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

        // 4. Get response from Ollama
        String response = ollamaService.generateResponse(prompt);

        return new Answer(response);
    }
}