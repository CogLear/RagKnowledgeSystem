package com.rks;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * RagKnowledgeSystem 核心应用启动类
 */
@SpringBootApplication
@EnableScheduling
@MapperScan(basePackages = {
        "com.rks.rag.dao.mapper",
        "com.rks.ingestion.dao.mapper",
        "com.rks.knowledge.dao.mapper",
        "com.rks.user.dao.mapper"
})
public class RagKnowledgeSystemApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagKnowledgeSystemApplication.class, args);
    }
}
