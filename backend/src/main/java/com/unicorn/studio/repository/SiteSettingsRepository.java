package com.unicorn.studio.repository;

import com.unicorn.studio.model.SiteSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SiteSettingsRepository extends JpaRepository<SiteSettings, Long> {

    Optional<SiteSettings> findBySettingKey(String key);

    List<SiteSettings> findAllByOrderByGroupNameAscSettingKeyAsc();
}
