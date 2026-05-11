package com.dataclub.llmcascade.repository;

import com.dataclub.llmcascade.model.LlmFailoverEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface LlmFailoverEventRepository extends JpaRepository<LlmFailoverEvent, Long> {

    List<LlmFailoverEvent> findTop50ByOrderByOccurredAtDesc();

    long countByOccurredAtAfter(LocalDateTime since);
}
