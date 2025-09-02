package edu.mcw.rgdai.controller;

import edu.mcw.rgdai.model.UrlRequest;
import edu.mcw.rgdai.reader.UrlDocumentReader;
import edu.mcw.rgdai.service.DocumentPreprocessor;
//import edu.mcw.scge.dao.implementation.ClinicalTrailDAO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.datasource.embedded.DataSourceFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
public class UrlController {
    private static final Logger LOG = LoggerFactory.getLogger(UrlController.class);

    // Change to use OpenAI vector store instead of default Ollama one
    private final VectorStore openaiVectorStore;
    private final DocumentPreprocessor preprocessor;

    public UrlController(@Qualifier("openaiVectorStore") VectorStore openaiVectorStore,
                         DocumentPreprocessor preprocessor) {
        this.openaiVectorStore = openaiVectorStore;
        this.preprocessor = preprocessor;
    }

    @PostMapping("/process-url")
    public ResponseEntity<?> processUrl(@RequestBody UrlRequest urlRequest) {
        String urlString = urlRequest.getUrl();
        LOG.info("Processing URL for OpenAI: {}", urlString);

        // Validate URL
        URL url;
        try {
            url = new URL(urlString);
        } catch (MalformedURLException e) {
            LOG.error("Invalid URL: {}", urlString, e);
            Map<String, String> response = new HashMap<>();
            response.put("error", "Invalid URL format");
            return ResponseEntity.badRequest().body(response);
        }
        // Fetch content from URL
        UrlDocumentReader documentReader = new UrlDocumentReader(urlString);
        List<Document> documents = documentReader.get();

        if (documents.isEmpty()) {
            LOG.error("Failed to fetch content from URL: {}", urlString);
            Map<String, String> response = new HashMap<>();
            response.put("error", "Failed to fetch content from the provided URL");
            return ResponseEntity.badRequest().body(response);
        }

//        documents.forEach(doc -> {
//            doc.getMetadata().put("filename", extractFilenameFromUrl(urlString));
//        });
        List<Document> documentsWithFilename = documents.stream()
                .map(doc -> {
                    Map<String, Object> mutableMetadata = new HashMap<>(doc.getMetadata());
                    mutableMetadata.put("filename", extractFilenameFromUrl(urlString));
                    return new Document(doc.getContent(), mutableMetadata);
                })
                .collect(Collectors.toList());

        documents = documentsWithFilename;
        // STEP 1: Universal preprocessing for ANY document type
        List<Document> preprocessedDocs = preprocessor.preprocessDocuments(documents);
        LOG.info("Preprocessed into {} clean documents", preprocessedDocs.size());

        if (preprocessedDocs.isEmpty()) {
            LOG.error("No usable content after preprocessing");
            throw new RuntimeException("Document preprocessing failed - no usable content found");
        }

        // STEP 2: Split into chunks with correct Spring AI settings
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(800)                // Target chunk size in tokens
                .withMinChunkSizeChars(200)        // Minimum characters per chunk
                .withMinChunkLengthToEmbed(50)     // Minimum length to embed
                .withMaxNumChunks(10000)           // Maximum number of chunks
                .withKeepSeparator(true)           // Keep separators for readability
                .build();

        List<Document> splitDocuments = splitter.apply(preprocessedDocs);
        LOG.info("Split into {} chunks after preprocessing", splitDocuments.size());

        // STEP 3: Quality filtering - ignored for now
        List<Document> qualityChunks = splitDocuments.stream()
                .filter(doc -> preprocessor.isQualityChunk(doc.getContent()))
                .toList();

        LOG.info("Quality filtered: {} chunks retained out of {}", qualityChunks.size(), splitDocuments.size());

        if (qualityChunks.isEmpty()) {
            LOG.error("No quality chunks after filtering");
            throw new RuntimeException("No quality content found after processing");
        }

        // Add to OpenAI vector store
        openaiVectorStore.add(splitDocuments);
//        openaiVectorStore.add(qualityChunks);
        LOG.info("Successfully added {} URL chunks to OpenAI vector store", splitDocuments.size());

        Map<String, Object> response = new HashMap<>();
        response.put("url", urlString);
        response.put("title", documents.get(0).getMetadata().getOrDefault("title", "Unknown"));
        response.put("chunkCount", splitDocuments.size());
        response.put("vectorStore", "OpenAI"); // Indicate which store was used

        return ResponseEntity.ok(response);
    }

    private String extractFilenameFromUrl(String urlString) {
        try {
            URL url = new URL(urlString);
            String path = url.getPath();

            // Extract the last part of the path or use the domain
            if (path != null && !path.isEmpty() && !path.equals("/")) {
                String[] pathParts = path.split("/");
                String lastPart = pathParts[pathParts.length - 1];
                if (!lastPart.isEmpty()) {
                    return lastPart;
                }
            }

            return url.getHost().replaceAll("\\.", "_");

        } catch (MalformedURLException e) {
            return "webpage_" + System.currentTimeMillis();
        }
    }
}