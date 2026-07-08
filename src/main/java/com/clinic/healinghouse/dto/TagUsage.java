package com.clinic.healinghouse.dto;

import com.clinic.healinghouse.entity.Tag;

/** Tag plus its usage counts, for the Tags management page. */
public record TagUsage(Tag tag, long serviceCount, long productCount) {
    public long totalCount() {
        return serviceCount + productCount;
    }
}
