package com.dataclub.llmcascade.repository;

import com.dataclub.llmcascade.model.AppSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppSettingRepository extends JpaRepository<AppSetting, String> {
}
