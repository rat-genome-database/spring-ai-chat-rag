//package edu.mcw.rgdai.config;
//
//import org.springframework.ai.embedding.EmbeddingModel;
//import org.springframework.ai.vectorstore.SimpleVectorStore;
//import org.springframework.ai.vectorstore.VectorStore;
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
//
//@Configuration
//public class VectorStoreConfig {
//
//    @Bean
//    VectorStore simpleVectorStore(EmbeddingModel embeddingModel) {
//        return new SimpleVectorStore(embeddingModel);
//    }
//}

package edu.mcw.rgdai.config;

        import org.springframework.ai.embedding.EmbeddingModel;
        import org.springframework.ai.vectorstore.VectorStore;
        import org.springframework.context.annotation.Bean;
        import org.springframework.context.annotation.Configuration;
        import edu.mcw.rgdai.repository.DocumentEmbeddingRepository;
        import edu.mcw.rgdai.vectorstore.PostgresVectorStore;

@Configuration
public class VectorStoreConfig {
    @Bean
    VectorStore vectorStore(EmbeddingModel embeddingModel, DocumentEmbeddingRepository repository) {
        return new PostgresVectorStore(repository, embeddingModel);
    }
}

