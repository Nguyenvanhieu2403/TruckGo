package com.truckgo.pricing.config;

import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.UUID;

public interface PricingConfigHistoryRepository extends Repository<PricingConfigHistory, UUID> {
    PricingConfigHistory save(PricingConfigHistory history);
    List<PricingConfigHistory> findByPricingConfigIdOrderByChangedAtDesc(UUID pricingConfigId);
}
