package com.dataclub.llmcascade.repository;

import com.dataclub.llmcascade.model.ProviderServer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProviderServerRepository extends JpaRepository<ProviderServer, String> {
    Optional<ProviderServer> findFirstByIsDefaultTrue();
}
