package edu.mcw.rgdai.vectorstore;

import com.pgvector.PGvector;
import edu.mcw.rgdai.model.DocumentEmbedding;
import edu.mcw.rgdai.repository.DocumentEmbeddingRepository;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
public class PostgresVectorStore implements VectorStore {
    private final DocumentEmbeddingRepository repository;
    private final EmbeddingModel embeddingModel;

    public PostgresVectorStore(DocumentEmbeddingRepository repository, EmbeddingModel embeddingModel) {
        this.repository = repository;
        this.embeddingModel = embeddingModel;
    }

    @Override
    public void add(List<Document> documents) {
        for (Document doc : documents) {
            float[] embedding = embeddingModel.embed(List.of(doc.getContent())).get(0);
            DocumentEmbedding docEmbedding = new DocumentEmbedding();
            docEmbedding.setChunk(doc.getContent());
            docEmbedding.setEmbedding(new PGvector(embedding));
            docEmbedding.setFileName(doc.getMetadata().getOrDefault("filename", "unknown").toString());
            docEmbedding.setCreatedAt(LocalDateTime.now());

            repository.save(docEmbedding);
        }
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        System.out.println("Starting similarity search for query: {}"+request.getQuery());
        EmbeddingResponse response = embeddingModel.embedForResponse(List.of(request.getQuery()));
        float[] queryEmbedding = response.getResults().get(0).getOutput();
        System.out.println("Generated embedding vector of size: {}"+queryEmbedding.length);
        List<DocumentEmbedding> nearest = repository.findNearestNeighbors(queryEmbedding, request.getTopK());
        System.out.println("Found {} nearest documents in database"+nearest.size());
        return nearest.stream()
                .map(de -> new Document(de.getChunk(), Map.of("filename", de.getFileName())))
                .collect(Collectors.toList());
    }

    @Override
    public Optional<Boolean> delete(List<String> ids) {
        throw new UnsupportedOperationException("Delete operation not implemented");
    }
}