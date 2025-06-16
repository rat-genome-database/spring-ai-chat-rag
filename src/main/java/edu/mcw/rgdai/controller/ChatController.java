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

        // Handle simple greetings without context
        if (isGreeting(question.getQuestion())) {
            return new Answer("Hello! I'm here to help you with questions about the documents in my knowledge base. What would you like to know?");
        }

        // 1. Retrieve relevant documents
        List<Document> documents = vectorStore.similaritySearch(
                SearchRequest.query(question.getQuestion())
                        .withTopK(8)// Get more documents for better context
                        .withSimilarityThreshold(0.3)

        );

        LOG.info("Retrieved {} documents from vector store", documents.size());

        // 2. Check if we have relevant context AND if it's actually related to the question
        if (documents.isEmpty()) {
            return new Answer("I don't have information about that topic in my knowledge base. Please try rephrasing your question or check if relevant documents have been uploaded.");
        }

        // 3. Quick relevance check - if the question seems completely unrelated to document content
        if (isGeneralKnowledgeQuestion(question.getQuestion(), documents)) {
            return new Answer("I don't have information about that in my knowledge base. Please ask questions related to the uploaded documents.");
        }

        // 3. Format the context with source information
        StringBuilder contextBuilder = new StringBuilder();
        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            String filename = doc.getMetadata().getOrDefault("filename", "unknown").toString();
//            contextBuilder.append(String.format("Source %d (from %s):\n%s\n\n",
//                    i + 1, filename, doc.getContent()));
            contextBuilder.append(String.format("From %s:\n%s\n\n",
                    filename, doc.getContent()));
//            String cleanFilename = filename.replace(".pdf", "").replace(".md", "").replace("-", " ");
//            contextBuilder.append(String.format("From %s:\n%s\n\n", cleanFilename, doc.getContent()));
        }

        String context = contextBuilder.toString();
        LOG.info("Context length: {} characters from {} sources", context.length(), documents.size());

        // 4. Create an improved prompt
        String prompt = createImprovedPrompt(context, question.getQuestion());

        // 5. Get response from Ollama
        String response = ollamaService.generateResponse(prompt);

        // 6. Post-process the response
        String finalResponse = postProcessResponse(response, documents);

        return new Answer(finalResponse);
    }

    private boolean isGreeting(String text) {
        String lowerText = text.toLowerCase().trim();
        return lowerText.matches("^(hi|hello|hey|good morning|good afternoon|good evening|how are you|what's up|greetings).*");
    }

    private boolean isGeneralKnowledgeQuestion(String question, List<Document> documents) {
        String lowerQuestion = question.toLowerCase();

        // Common patterns for general knowledge questions
        String[] generalPatterns = {
                "how tall is", "how high is", "what is the height of",
                "when was.*born", "when did.*die", "who invented",
                "what is the capital of", "what is the population of",
                "how far is", "what time is it", "what's the weather",
                "who is the president", "who is the ceo of.*(?!.*" + getDocumentTopics(documents) + ")",
                "what year did", "how old is", "what color is",
                "recipe for", "how to cook", "lyrics to"
        };

        for (String pattern : generalPatterns) {
            if (lowerQuestion.matches(".*" + pattern + ".*")) {
                // Double-check: if the documents actually contain related content, allow it
                if (documentsContainRelevantTerms(question, documents)) {
                    return false; // Not a general knowledge question if our docs have related content
                }
                LOG.info("Detected general knowledge question: {}", question);
                return true;
            }
        }

        return false;
    }

    private String getDocumentTopics(List<Document> documents) {
        // Extract key terms from documents to help identify if question might be relevant
        return documents.stream()
                .map(doc -> doc.getMetadata().getOrDefault("filename", "").toString())
                .collect(Collectors.joining("|"));
    }

    private boolean documentsContainRelevantTerms(String question, List<Document> documents) {
        String[] questionWords = question.toLowerCase().split("\\s+");

        // Check if any significant words from the question appear in the documents
        for (Document doc : documents) {
            String content = doc.getContent().toLowerCase();
            long matchingWords = 0;

            for (String word : questionWords) {
                if (word.length() > 3 && content.contains(word)) { // Only check significant words
                    matchingWords++;
                }
            }

            // If more than 20% of significant words match, consider it potentially relevant
            if (matchingWords > questionWords.length * 0.2) {
                return true;
            }
        }

        return false;
    }

    private String createImprovedPrompt(String context, String question) {
        return String.format("""
            You are a helpful AI assistant that ONLY answers questions based on the provided document context. You must not use any external knowledge or general information.

            **CRITICAL INSTRUCTIONS:**
            1. **ONLY use the provided context**: Answer ONLY based on information explicitly found in the context below
            2. **No external knowledge**: Do NOT answer questions about general topics, current events, or information not in the context
            3. **Be strict**: If the answer is not in the context, you MUST say "I don't have information about that in my knowledge base"
            4. **Cite sources**: When you do find relevant information, mention which document it came from
            5. **Be helpful when context exists**: If the context contains relevant information, provide a detailed answer

            **RESPONSE RULES:**
            - If the context contains relevant information: Provide a comprehensive, detailed response with source citations
            - If the context partially answers the question: Answer only what the context supports and indicate what's missing
            - If the context is not relevant or doesn't contain the answer: Respond with "I don't have information about that in my knowledge base. Please ask questions related to the uploaded documents."

            **EXAMPLES OF WHAT NOT TO ANSWER:**
            - General knowledge questions (height of mountains, historical dates not in documents, etc.)
            - Current events not mentioned in the documents
            - Mathematical calculations not related to document content
            - Personal advice or opinions

            **CONTEXT FROM DOCUMENTS:**
            %s

            **USER QUESTION:** %s

            **YOUR RESPONSE:**
            """, context, question);
    }

    private String postProcessResponse(String response, List<Document> sources) {
        // If response seems incomplete, add helpful information about sources
        if (response.toLowerCase().contains("don't contain") ||
                response.toLowerCase().contains("not available") ||
                response.toLowerCase().contains("don't have")) {

            StringBuilder sourceInfo = new StringBuilder("\n\nAvailable documents in knowledge base:\n");
            sources.stream()
                    .map(doc -> doc.getMetadata().getOrDefault("filename", "unknown").toString())
                    .distinct()
                    .forEach(filename -> sourceInfo.append("• ").append(filename).append("\n"));

            return response + sourceInfo.toString();
        }

        return response;
    }
}

//old code
////package edu.mcw.rgdai.controller;
////
////import edu.mcw.rgdai.model.Answer;
////import edu.mcw.rgdai.model.Question;
////import org.slf4j.Logger;
////import org.slf4j.LoggerFactory;
////import org.springframework.ai.chat.client.ChatClient;
////import org.springframework.ai.chat.client.advisor.PromptChatMemoryAdvisor;
////import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor;
////import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
////import org.springframework.ai.chat.memory.InMemoryChatMemory;
////import org.springframework.ai.vectorstore.SearchRequest;
////import org.springframework.ai.vectorstore.VectorStore;
////import org.springframework.security.core.Authentication;
////import org.springframework.web.bind.annotation.*;
////@RestController
////@RequestMapping("/chat")
////public class ChatController {
////    private static final Logger LOG = LoggerFactory.getLogger(ChatController.class);
////    private final ChatClient chatClient;
////    private static final String CHAT_MEMORY_CONVERSATION_ID_KEY = "conversationId";
////
////    public ChatController(ChatClient.Builder chatClientBuilder, VectorStore vectorStore) {
////        LOG.info("Initializing ChatController");
////        this.chatClient = chatClientBuilder
////                .defaultAdvisors(
////                        new SimpleLoggerAdvisor(),
////                        new QuestionAnswerAdvisor(vectorStore, SearchRequest.defaults().withTopK(5)
////                                .withSimilarityThreshold(0.5)), // Lower threshold),  // Get top 5 matches
////                        new PromptChatMemoryAdvisor(new InMemoryChatMemory())
////                )
////                .build();
////        LOG.info("ChatClient initialized successfully");
////    }
////
////    @PostMapping
////    public Answer chat(@RequestBody Question question, Authentication user) {
////        LOG.info("Received question: {}", question.getQuestion());
////        Answer answer = chatClient.prompt()
////                .user(question.getQuestion())
////                .advisors(
////                        advisorSpec -> advisorSpec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, user.getPrincipal())
////                )
////                .call()
////                .entity(Answer.class);
////        LOG.info("Generated answer: {}", answer.getAnswer());
////        return answer;
////    }
////}
//package edu.mcw.rgdai.controller;
//
//import edu.mcw.rgdai.model.Answer;
//import edu.mcw.rgdai.model.Question;
//import edu.mcw.rgdai.service.OllamaService;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.ai.document.Document;
//import org.springframework.ai.vectorstore.SearchRequest;
//import org.springframework.ai.vectorstore.VectorStore;
//import org.springframework.security.core.Authentication;
//import org.springframework.web.bind.annotation.*;
//
//import java.util.List;
//import java.util.stream.Collectors;
//
//@RestController
//@RequestMapping("/chat")
//public class ChatController {
//    private static final Logger LOG = LoggerFactory.getLogger(ChatController.class);
//    private final VectorStore vectorStore;
//    private final OllamaService ollamaService;
//
//    public ChatController(VectorStore vectorStore, OllamaService ollamaService) {
//        this.vectorStore = vectorStore;
//        this.ollamaService = ollamaService;
//        LOG.info("ChatController initialized successfully");
//    }
//
//    @PostMapping
//    public Answer chat(@RequestBody Question question, Authentication user) {
//        LOG.info("Received question: {}", question.getQuestion());
//
//        // 1. Retrieve relevant documents
//        List<Document> documents = vectorStore.similaritySearch(
//                SearchRequest.query(question.getQuestion()).withTopK(10)
//        );
//
//        // 2. Format the context
//        String context = documents.stream()
//                .map(Document::getContent)
//                .collect(Collectors.joining("\n\n"));
//
//        LOG.info("Retrieved context: {} characters", context.length());
//
//        // 3. Create prompt with context
//
//        //using this prompt
//        String prompt=String.format("""
//      You are an AI assistant that ONLY answers questions based on the provided context information.
//
//    IMPORTANT INSTRUCTIONS:
//    - If the answer is not contained in the context except for greeting messages such as hi, how are you doing etc, respond with ONLY: "I don't have information about that in my knowledge base."
//    - If the user says "hi" or greets you, respond EXACTLY: "Hello! I can help you find information from the uploaded documents. What would you like to know?"
//    - Do not use any knowledge outside of the provided context
//    - Do not make up or infer information not explicitly stated in the context
//
//    Context:
//    %s
//
//    Question: %s
//
//    Answer (based ONLY on the context above):
//    """, context, question.getQuestion());
//        String enhancedPrompt = String.format("""
//SYSTEM: You must follow these rules exactly. No exceptions.
//
//RULE 1: If user says "hi", "hello", or any greeting, respond ONLY with: "Hello! I can help you find information from the uploaded documents. What would you like to know?"
//
//RULE 2: If the question cannot be answered from the context below, respond ONLY with: "I don't have information about that in my knowledge base."
//
//RULE 3: Never explain what the context contains. Never mention document topics unless directly asked.
//
//RULE 4: Only answer if the exact information is in the context below.
//
//Context: %s
//
//User Question: %s
//
//Response:""", context, question.getQuestion());
//
//        // 4. Get response from Ollama
//        String response = ollamaService.generateResponse(enhancedPrompt);
//        System.out.println("Context length: {} characters"+context.length());
//        return new Answer(response);
//    }
//}