package com.truckgo.marketplace;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketplaceFilterTest {
    private static MarketplaceFilter filter(String sortBy, String direction, int page, int size) {
        return new MarketplaceFilter("  ", " Hà Nội ", null, null, null, null, null, null, null, null, null, sortBy, direction, page, size);
    }

    @Test void defaultsToSoonestPickupFirstAndClampsPaging() {
        MarketplaceFilter f = filter(null, null, -3, 500);
        assertThat(f.sort()).isEqualTo(MarketplaceFilter.Sort.PICKUP_TIME);
        assertThat(f.ascending()).isTrue();
        assertThat(f.page()).isZero();
        assertThat(f.size()).isEqualTo(MarketplaceFilter.MAX_SIZE);
        assertThat(f.keyword()).isNull();
        assertThat(f.pickupProvince()).isEqualTo("Hà Nội");
    }

    @Test void acceptsCamelCaseSortKeysAndRejectsAnythingElse() {
        assertThat(filter("publishedAt", null, 0, 20).sort()).isEqualTo(MarketplaceFilter.Sort.PUBLISHED_AT);
        assertThat(filter("price", null, 0, 20).ascending()).isFalse();
        assertThat(filter("price", "ASC", 0, 20).ascending()).isTrue();
        assertThatThrownBy(() -> filter("s.id; drop table shipments", null, 0, 20).sort()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> filter("price", "sideways", 0, 20).ascending()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsInvertedRanges() {
        assertThatThrownBy(() -> new MarketplaceFilter(null, null, null, null, null, null, new BigDecimal("10"), new BigDecimal("5"),
                null, null, null, null, null, 0, 20)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void likePatternTreatsWildcardsLiterally() {
        assertThat(MarketplaceQuery.contains("50%_off\\")).isEqualTo("%50\\%\\_off\\\\%");
    }
}
