package com.truckgo.pricing.config;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PricingServiceFeeRepository extends JpaRepository<PricingServiceFee, UUID> {
    List<PricingServiceFee> findByPricingConfigIdOrderByServiceCodeAscServiceOptionValueAsc(UUID pricingConfigId);
    List<PricingServiceFee> findByPricingConfigIdAndActiveTrue(UUID pricingConfigId);
    List<PricingServiceFee> findByPricingConfigIdInAndActiveTrue(Collection<UUID> pricingConfigIds);
}
