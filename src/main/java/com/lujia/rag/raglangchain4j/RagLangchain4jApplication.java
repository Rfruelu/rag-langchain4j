package com.lujia.rag.raglangchain4j;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RagLangchain4jApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagLangchain4jApplication.class, args);
    }

}
