package com.payflow.infrastructure.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Value("${payflow.kafka.topics.transactions}")
    private String transactionsTopic;

    @Bean
    public NewTopic transactionsTopic() {
        return TopicBuilder.name(transactionsTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic transactionsDltTopic() {
        return TopicBuilder.name(transactionsTopic + ".DLT")
                .partitions(3)
                .replicas(1)
                .build();
    }
}