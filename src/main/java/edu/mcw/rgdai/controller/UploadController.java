package edu.mcw.rgdai.controller;

import edu.mcw.rgdai.model.UploadResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;

@RestController
public class UploadController {
    private static final Logger LOG = LoggerFactory.getLogger(UploadController.class);
    private final VectorStore vectorStore;

    public UploadController(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @PostMapping("/upload")
    public UploadResponse upload(@RequestParam("file") MultipartFile file) throws IOException {
        LOG.info("Starting file upload: {}", file.getOriginalFilename());

        // Create temp directory and save file
        Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"), "rgdai-uploads");
        Files.createDirectories(tempDir);
        Path destinationFile = tempDir.resolve(file.getOriginalFilename());

        try (InputStream inputStream = file.getInputStream()) {
            Files.copy(inputStream, destinationFile, StandardCopyOption.REPLACE_EXISTING);
            LOG.info("File saved to: {}", destinationFile);
        }

        // Read and split document
        TikaDocumentReader documentReader = new TikaDocumentReader(destinationFile.toUri().toString());
        List<Document> documents = documentReader.get();
        documents.forEach(doc -> {
            doc.getMetadata().put("filename", file.getOriginalFilename());
        });
        LOG.info("Initial document size: {}", documents.get(0).getContent().length());

        // Create TokenTextSplitter with default settings
        TokenTextSplitter splitter = new TokenTextSplitter();
//        TokenTextSplitter splitter = TokenTextSplitter.builder()
//                .withChunkSize(512)  // Target size in tokens
//                .withMinChunkSizeChars(350)  // Default from class
//                .withMinChunkLengthToEmbed(5)  // Default from class
//                .withMaxNumChunks(10000)  // Default from class
//                .withKeepSeparator(true)  // Keep line separators
//                .build();
        List<Document> splitDocuments = splitter.apply(documents);
        LOG.info("----------------------------------------");
        LOG.info("Document split into {} chunks", splitDocuments.size());
        for (int i = 0; i < splitDocuments.size(); i++) {
            String content = splitDocuments.get(i).getContent();
            LOG.info("----------------------------------------");
            LOG.info("Chunk {} - Size: {} characters, Preview: {}...",
                    i, content.length(),
                    content.substring(0, Math.min(100, content.length())));
            LOG.info(splitDocuments.get(i).getContent());
        }
        LOG.info("----------------------------------------");
        vectorStore.add(splitDocuments);
        LOG.info("All chunks added to vector store successfully");
        LOG.info("File processing completed for: {}", file.getOriginalFilename());

        return new UploadResponse(file.getOriginalFilename(), file.getContentType(), file.getSize());
    }
}