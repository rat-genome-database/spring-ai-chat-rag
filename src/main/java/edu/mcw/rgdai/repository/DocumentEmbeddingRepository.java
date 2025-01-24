package edu.mcw.rgdai.repository;

import edu.mcw.rgdai.model.DocumentEmbedding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DocumentEmbeddingRepository extends JpaRepository<DocumentEmbedding, Long> {

    // Find nearest neighbors using cosine distance
    @Query(value = "SELECT * FROM document_embeddings ORDER BY embedding <=> CAST(:queryEmbedding AS vector) LIMIT :k", nativeQuery = true)
    List<DocumentEmbedding> findNearestNeighbors(@Param("queryEmbedding") float[] queryEmbedding, @Param("k") int k);

    // Find by filename
    List<DocumentEmbedding> findByFileName(String fileName);

    // Optional: Find by chunk containing text (case-insensitive)
    @Query("SELECT d FROM DocumentEmbedding d WHERE LOWER(d.chunk) LIKE LOWER(CONCAT('%', :text, '%'))")
    List<DocumentEmbedding> findByChunkContainingIgnoreCase(@Param("text") String text);
}