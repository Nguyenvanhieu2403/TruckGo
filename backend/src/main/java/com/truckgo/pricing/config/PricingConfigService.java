package com.truckgo.pricing.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.truckgo.catalog.CatalogVehicleType;
import com.truckgo.catalog.CatalogVehicleTypeRepository;
import com.truckgo.catalog.ServiceDefinition;
import com.truckgo.catalog.ServiceDefinitionRepository;
import com.truckgo.catalog.ServiceOption;
import com.truckgo.catalog.ServiceValueType;
import com.truckgo.common.exception.ConflictException;
import com.truckgo.common.exception.FieldErrorException;
import com.truckgo.common.exception.ResourceNotFoundException;
import com.truckgo.common.response.PageResponse;
import com.truckgo.pricing.config.ActivePricingCache.ActiveRates;
import com.truckgo.pricing.dto.PricingConfigDtos.ConfigRequest;
import com.truckgo.pricing.dto.PricingConfigDtos.ConfigResponse;
import com.truckgo.pricing.dto.PricingConfigDtos.ExtraFeeRequest;
import com.truckgo.pricing.dto.PricingConfigDtos.ExtraFeeResponse;
import com.truckgo.pricing.dto.PricingConfigDtos.HistoryResponse;
import com.truckgo.pricing.dto.PricingConfigDtos.ServiceFeeRequest;
import com.truckgo.pricing.dto.PricingConfigDtos.ServiceFeeResponse;
import com.truckgo.pricing.dto.PricingConfigDtos.TimeRuleRequest;
import com.truckgo.pricing.dto.PricingConfigDtos.TimeRuleResponse;
import com.truckgo.pricing.engine.FeeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Admin management of price tables and service fees. Every change is written to pricing_config_histories with a JSON
 * before/after, and the active-pricing cache is evicted after commit. Existing quotes are never touched: they hold
 * their own snapshot.
 */
@Service
public class PricingConfigService {
    private static final Logger log = LoggerFactory.getLogger(PricingConfigService.class);

    private final PricingConfigRepository configs;
    private final PricingServiceFeeRepository serviceFees;
    private final PricingConfigHistoryRepository history;
    private final CatalogVehicleTypeRepository vehicleTypes;
    private final ServiceDefinitionRepository serviceDefinitions;
    private final ActivePricingCache cache;
    private final ObjectMapper json;
    private final Clock clock;

    public PricingConfigService(PricingConfigRepository configs, PricingServiceFeeRepository serviceFees, PricingConfigHistoryRepository history,
                                CatalogVehicleTypeRepository vehicleTypes, ServiceDefinitionRepository serviceDefinitions, ActivePricingCache cache,
                                ObjectMapper json, Clock clock) {
        this.configs = configs;
        this.serviceFees = serviceFees;
        this.history = history;
        this.vehicleTypes = vehicleTypes;
        this.serviceDefinitions = serviceDefinitions;
        this.cache = cache;
        this.json = json;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ price tables

    @Transactional(readOnly = true)
    public PageResponse<ConfigResponse> search(String vehicleTypeCode, Boolean active, Pageable pageable) {
        String vehicle = vehicleTypeCode == null || vehicleTypeCode.isBlank() ? null : vehicleTypeCode.trim().toUpperCase(Locale.ROOT);
        Map<String, String> names = vehicleNames();
        return PageResponse.of(configs.search(vehicle, active, pageable), c -> toResponse(c, List.of(), names));
    }

    @Transactional(readOnly = true)
    public ConfigResponse get(UUID id) {
        PricingConfig config = require(id);
        return toResponse(config, serviceFees.findByPricingConfigIdOrderByServiceCodeAscServiceOptionValueAsc(id), vehicleNames());
    }

    @Transactional
    public ConfigResponse create(UUID adminId, ConfigRequest request) {
        PricingConfig config = new PricingConfig(adminId);
        applyRequest(config, request);
        configs.saveAndFlush(config);
        ConfigResponse created = toResponse(config, List.of(), vehicleNames());
        record(config.getId(), "CREATED", null, created, adminId);
        cache.evictAfterCommit();
        log.info("Pricing config created id={} vehicle={} by={}", config.getId(), config.getVehicleTypeCode(), adminId);
        return created;
    }

    @Transactional
    public ConfigResponse update(UUID adminId, UUID id, ConfigRequest request) {
        PricingConfig config = require(id);
        if (request.version() == null) throw new IllegalArgumentException("Thiếu phiên bản dữ liệu (version)");
        if (request.version() != config.getVersion()) throw new ConflictException("Bảng giá đã được thay đổi ở nơi khác, vui lòng tải lại");
        List<PricingServiceFee> fees = serviceFees.findByPricingConfigIdOrderByServiceCodeAscServiceOptionValueAsc(id);
        ConfigResponse before = toResponse(config, fees, vehicleNames());
        applyRequest(config, request);
        configs.saveAndFlush(config);
        ConfigResponse after = toResponse(config, fees, vehicleNames());
        record(id, "UPDATED", before, after, adminId);
        cache.evictAfterCommit();
        return after;
    }

    @Transactional
    public ConfigResponse setActive(UUID adminId, UUID id, boolean active) {
        PricingConfig config = require(id);
        if (config.isActive() == active) throw new ConflictException(active ? "Bảng giá đang được bật" : "Bảng giá đang được tắt");
        if (active) assertNoOverlap(config.getId(), config.getVehicleTypeCode(), config.getEffectiveFrom(), config.getEffectiveTo());
        List<PricingServiceFee> fees = serviceFees.findByPricingConfigIdOrderByServiceCodeAscServiceOptionValueAsc(id);
        ConfigResponse before = toResponse(config, fees, vehicleNames());
        config.setActive(active);
        configs.saveAndFlush(config);
        ConfigResponse after = toResponse(config, fees, vehicleNames());
        record(id, active ? "ACTIVATED" : "DEACTIVATED", before, after, adminId);
        cache.evictAfterCommit();
        return after;
    }

    @Transactional(readOnly = true)
    public List<HistoryResponse> history(UUID id) {
        require(id);
        return history.findByPricingConfigIdOrderByChangedAtDesc(id).stream()
                .map(h -> new HistoryResponse(h.getId(), h.getAction(), h.getOldValue(), h.getNewValue(), h.getChangedBy(), h.getChangedAt())).toList();
    }

    // ------------------------------------------------------------------ service fees

    @Transactional(readOnly = true)
    public List<ServiceFeeResponse> serviceFees(UUID configId, boolean includeInactive) {
        require(configId);
        return serviceFees.findByPricingConfigIdOrderByServiceCodeAscServiceOptionValueAsc(configId).stream()
                .filter(f -> includeInactive || f.isActive()).map(this::toResponse).toList();
    }

    @Transactional
    public ServiceFeeResponse createServiceFee(UUID adminId, ServiceFeeRequest request) {
        require(request.pricingConfigId());
        PricingServiceFee fee = new PricingServiceFee(request.pricingConfigId());
        applyServiceFee(fee, request);
        serviceFees.saveAndFlush(fee);
        ServiceFeeResponse created = toResponse(fee);
        record(fee.getPricingConfigId(), "SERVICE_FEE_CREATED", null, created, adminId);
        cache.evictAfterCommit();
        return created;
    }

    @Transactional
    public ServiceFeeResponse updateServiceFee(UUID adminId, UUID feeId, ServiceFeeRequest request) {
        PricingServiceFee fee = serviceFees.findById(feeId).filter(PricingServiceFee::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy phí dịch vụ"));
        if (!fee.getPricingConfigId().equals(request.pricingConfigId())) throw FieldErrorException.invalid("pricingConfigId", "Không thể chuyển phí sang bảng giá khác");
        if (request.version() == null || request.version() != fee.getVersion()) throw new ConflictException("Phí dịch vụ đã được thay đổi ở nơi khác, vui lòng tải lại");
        ServiceFeeResponse before = toResponse(fee);
        applyServiceFee(fee, request);
        serviceFees.saveAndFlush(fee);
        ServiceFeeResponse after = toResponse(fee);
        record(fee.getPricingConfigId(), "SERVICE_FEE_UPDATED", before, after, adminId);
        cache.evictAfterCommit();
        return after;
    }

    /** Soft delete: the row stays for history; it no longer applies to new quotes. */
    @Transactional
    public void deleteServiceFee(UUID adminId, UUID feeId) {
        PricingServiceFee fee = serviceFees.findById(feeId).filter(PricingServiceFee::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy phí dịch vụ"));
        ServiceFeeResponse before = toResponse(fee);
        fee.deactivate();
        serviceFees.saveAndFlush(fee);
        record(fee.getPricingConfigId(), "SERVICE_FEE_DELETED", before, null, adminId);
        cache.evictAfterCommit();
    }

    // ------------------------------------------------------------------ used by the quote engine

    /** Price table effective at {@code at} for a vehicle type, if any (cached). */
    public Optional<ActiveRates> effectiveFor(String vehicleTypeCode, Instant at) {
        return activeRates().stream().filter(r -> r.rates().vehicleTypeCode().equals(vehicleTypeCode) && r.effectiveAt(at)).findFirst();
    }

    /** Vehicle types that have a price table effective at {@code at}. */
    public Set<String> pricedVehicleTypes(Instant at) {
        return activeRates().stream().filter(r -> r.effectiveAt(at)).map(r -> r.rates().vehicleTypeCode()).collect(Collectors.toSet());
    }

    private List<ActiveRates> activeRates() {
        return cache.get(() -> {
            List<PricingConfig> active = configs.findByActiveTrue();
            Map<UUID, List<PricingServiceFee>> fees = serviceFees.findByPricingConfigIdInAndActiveTrue(active.stream().map(PricingConfig::getId).toList())
                    .stream().collect(Collectors.groupingBy(PricingServiceFee::getPricingConfigId));
            return active.stream().map(c -> new ActiveRates(PricingRatesMapper.toRates(c, fees.getOrDefault(c.getId(), List.of())), c.getEffectiveFrom(), c.getEffectiveTo())).toList();
        });
    }

    // ------------------------------------------------------------------ validation and mapping

    private void applyRequest(PricingConfig config, ConfigRequest r) {
        Map<String, String> errors = new LinkedHashMap<>();
        String vehicle = r.vehicleTypeCode().trim().toUpperCase(Locale.ROOT);
        if (!vehicleTypes.existsById(vehicle)) errors.put("vehicleTypeCode", "Loại xe không tồn tại");
        Instant from = r.effectiveFrom().toInstant();
        Instant to = Optional.ofNullable(r.effectiveTo()).map(OffsetDateTime::toInstant).orElse(null);
        if (to != null && !to.isAfter(from)) errors.put("effectiveTo", "Thời điểm kết thúc phải sau thời điểm bắt đầu");
        if (to != null && !to.isAfter(clock.instant())) errors.put("effectiveTo", "Thời điểm kết thúc đã qua; bảng giá sẽ không bao giờ được áp dụng");
        List<PricingTimeRule> rules = timeRules(r.timeRules() == null ? List.of() : r.timeRules(), errors);
        List<PricingExtraFee> extras = extraFees(r.extraFees() == null ? List.of() : r.extraFees(), errors);
        if (!errors.isEmpty()) throw new FieldErrorException(HttpStatus.BAD_REQUEST, "Dữ liệu bảng giá không hợp lệ", errors);
        if (config.isActive()) assertNoOverlap(config.getId(), vehicle, from, to);
        config.apply(r.name().trim(), vehicle, r.basePrice(), r.pricePerKm(), r.includedDistanceKm(), r.includedWeightKg(), r.pricePerExtraKg(),
                r.includedVolumeM3(), r.pricePerExtraM3(), r.additionalStopPrice(), r.minimumPrice(), from, to);
        config.replaceRules(rules, extras);
    }

    private List<PricingTimeRule> timeRules(List<TimeRuleRequest> requests, Map<String, String> errors) {
        for (int i = 0; i < requests.size(); i++) {
            TimeRuleRequest t = requests.get(i);
            if (t.startTime().equals(t.endTime())) errors.put("timeRules[" + i + "].endTime", "Giờ kết thúc phải khác giờ bắt đầu");
            checkFee("timeRules[" + i + "]", t.feeType(), t.amount(), t.percentage(), errors, false);
        }
        return requests.stream().map(t -> new PricingTimeRule(t.name().trim(), blankToNull(t.daysOfWeek()), t.startTime(), t.endTime(), t.feeType(),
                zeroIfNull(t.amount()), zeroIfNull(t.percentage()))).toList();
    }

    private List<PricingExtraFee> extraFees(List<ExtraFeeRequest> requests, Map<String, String> errors) {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < requests.size(); i++) {
            ExtraFeeRequest e = requests.get(i);
            if (!codes.add(e.code())) errors.put("extraFees[" + i + "].code", "Mã phí bị trùng");
            checkFee("extraFees[" + i + "]", e.feeType(), e.amount(), e.percentage(), errors, false);
        }
        return requests.stream().map(e -> new PricingExtraFee(e.code(), e.name().trim(), e.feeType(), zeroIfNull(e.amount()), zeroIfNull(e.percentage()))).toList();
    }

    private void applyServiceFee(PricingServiceFee fee, ServiceFeeRequest r) {
        Map<String, String> errors = new LinkedHashMap<>();
        String code = r.serviceCode().trim().toUpperCase(Locale.ROOT);
        String option = blankToNull(r.serviceOptionValue());
        Optional<ServiceDefinition> def = serviceDefinitions.findById(code);
        if (def.isEmpty()) errors.put("serviceCode", "Dịch vụ không tồn tại trong danh mục");
        else if (option != null) {
            if (def.get().getValueType() != ServiceValueType.OPTION) errors.put("serviceOptionValue", "Dịch vụ này không có lựa chọn");
            else if (def.get().getOptions().stream().map(ServiceOption::getValue).noneMatch(option::equals)) errors.put("serviceOptionValue", "Lựa chọn không hợp lệ");
        }
        checkFee("", r.pricingType(), r.amount(), r.percentage(), errors, true);
        if (!errors.isEmpty()) throw new FieldErrorException(HttpStatus.BAD_REQUEST, "Dữ liệu phí dịch vụ không hợp lệ", errors);
        boolean duplicate = serviceFees.findByPricingConfigIdAndActiveTrue(fee.getPricingConfigId()).stream()
                .anyMatch(f -> !f.getId().equals(fee.getId()) && f.getServiceCode().equals(code) && Objects.equals(f.getServiceOptionValue(), option));
        if (duplicate) throw FieldErrorException.conflict("serviceCode", "Dịch vụ này đã có phí trong bảng giá");
        fee.apply(code, option, r.pricingType(), zeroIfNull(r.amount()), zeroIfNull(r.percentage()));
    }

    /** Type-specific rules: amount-based types need an amount, PERCENTAGE needs a percentage, FREE needs neither. */
    private static void checkFee(String prefix, FeeType type, BigDecimal amount, BigDecimal percentage, Map<String, String> errors, boolean serviceTypesAllowed) {
        String p = prefix.isEmpty() ? "" : prefix + ".";
        if (!serviceTypesAllowed && (type == FeeType.PER_UNIT || type == FeeType.PER_STOP || type == FeeType.FREE)) { errors.put(p + "feeType", "Chỉ hỗ trợ FIXED hoặc PERCENTAGE"); return; }
        BigDecimal a = zeroIfNull(amount);
        BigDecimal pct = zeroIfNull(percentage);
        switch (type) {
            case FIXED, PER_UNIT, PER_STOP -> { if (a.signum() <= 0) errors.put(p + "amount", "Nhập số tiền lớn hơn 0"); if (pct.signum() != 0) errors.put(p + "percentage", "Không dùng phần trăm với cách tính này"); }
            case PERCENTAGE -> { if (pct.signum() <= 0) errors.put(p + "percentage", "Nhập phần trăm lớn hơn 0"); if (a.signum() != 0) errors.put(p + "amount", "Không dùng số tiền với cách tính phần trăm"); }
            case FREE -> { if (a.signum() != 0 || pct.signum() != 0) errors.put(p + "amount", "Dịch vụ miễn phí không có số tiền"); }
        }
    }

    private void assertNoOverlap(UUID selfId, String vehicle, Instant from, Instant to) {
        boolean overlap = configs.findByVehicleTypeCodeAndActiveTrue(vehicle).stream()
                .filter(c -> !c.getId().equals(selfId))
                .anyMatch(c -> (c.getEffectiveTo() == null || c.getEffectiveTo().isAfter(from)) && (to == null || c.getEffectiveFrom().isBefore(to)));
        if (overlap) throw FieldErrorException.conflict("effectiveFrom", "Thời gian hiệu lực trùng với một bảng giá đang bật của cùng loại xe");
    }

    private PricingConfig require(UUID id) { return configs.findById(id).orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy bảng giá")); }

    private void record(UUID configId, String action, Object before, Object after, UUID adminId) {
        history.save(new PricingConfigHistory(configId, action, toJson(before), toJson(after), adminId));
    }

    private String toJson(Object value) {
        if (value == null) return null;
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException(ex); }
    }

    private Map<String, String> vehicleNames() {
        return vehicleTypes.findAll().stream().collect(Collectors.toMap(CatalogVehicleType::getCode, CatalogVehicleType::getName));
    }

    private ConfigResponse toResponse(PricingConfig c, List<PricingServiceFee> fees, Map<String, String> vehicleNames) {
        return new ConfigResponse(c.getId(), c.getName(), c.getVehicleTypeCode(), vehicleNames.get(c.getVehicleTypeCode()), c.getBasePrice(), c.getPricePerKm(),
                c.getIncludedDistanceKm(), c.getIncludedWeightKg(), c.getPricePerExtraKg(), c.getIncludedVolumeM3(), c.getPricePerExtraM3(), c.getAdditionalStopPrice(),
                c.getMinimumPrice(), c.getCurrency(), c.getEffectiveFrom(), c.getEffectiveTo(), c.isActive(), c.isEffectiveAt(clock.instant()),
                c.getTimeRules().stream().map(t -> new TimeRuleResponse(t.getName(), t.getDaysOfWeek(), t.getStartTime(), t.getEndTime(), t.getFeeType(), t.getAmount(), t.getPercentage())).toList(),
                c.getExtraFees().stream().map(e -> new ExtraFeeResponse(e.getCode(), e.getName(), e.getFeeType(), e.getAmount(), e.getPercentage())).toList(),
                fees.stream().filter(PricingServiceFee::isActive).map(this::toResponse).toList(), c.getCreatedAt(), c.getUpdatedAt(), c.getVersion());
    }

    private ServiceFeeResponse toResponse(PricingServiceFee f) {
        Optional<ServiceDefinition> def = serviceDefinitions.findById(f.getServiceCode());
        String optionLabel = f.getServiceOptionValue() == null ? null : def.flatMap(d -> d.getOptions().stream().filter(o -> o.getValue().equals(f.getServiceOptionValue()))
                .map(ServiceOption::getLabel).findFirst()).orElse(f.getServiceOptionValue());
        return new ServiceFeeResponse(f.getId(), f.getPricingConfigId(), f.getServiceCode(), def.map(ServiceDefinition::getName).orElse(f.getServiceCode()),
                f.getServiceOptionValue(), optionLabel, f.getPricingType(), f.getAmount(), f.getPercentage(), f.isActive(), f.getVersion(), f.getUpdatedAt());
    }

    private static BigDecimal zeroIfNull(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    private static String blankToNull(String v) { return v == null || v.isBlank() ? null : v.trim(); }
}
