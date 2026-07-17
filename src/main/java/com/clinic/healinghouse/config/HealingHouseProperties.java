package com.clinic.healinghouse.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Central home for business thresholds, tag names, and formatting constants that were previously
 * scattered as {@code private static final} fields across controllers/services (see
 * requirements/Bug_Report_v3.md's follow-up audit). Every default below matches the value it
 * replaced exactly — this only changes where each value lives, not what it is.
 */
@Component
@ConfigurationProperties(prefix = "healinghouse")
@Getter
@Setter
public class HealingHouseProperties {

    private final Appointment appointment = new Appointment();
    private final Pagination pagination = new Pagination();
    private final Autocomplete autocomplete = new Autocomplete();
    private final Dashboard dashboard = new Dashboard();
    private final Reports reports = new Reports();
    private final Commission commission = new Commission();
    private final Owner owner = new Owner();
    private final Currency currency = new Currency();
    private final Export export = new Export();
    private final Security security = new Security();

    @Getter
    @Setter
    public static class Appointment {
        private int maxDurationMinutes = 24 * 60;
    }

    @Getter
    @Setter
    public static class Pagination {
        private int maxPageSize = 100;
    }

    @Getter
    @Setter
    public static class Autocomplete {
        private int patientMaxSuggestions = 8;
        private int tagMaxSuggestions = 10;
        private int comboMaxSuggestions = 8;
    }

    @Getter
    @Setter
    public static class Dashboard {
        private int trendDays = 7;
        private int tagBreakdownDays = 30;
    }

    @Getter
    @Setter
    public static class Reports {
        private int defaultRangeDays = 30;
        private String trendLabelFormat = "dd MMM";
    }

    @Getter
    @Setter
    public static class Commission {
        private String commissionTag = "Commission";
        private String bonusTag = "Bonus";
    }

    @Getter
    @Setter
    public static class Owner {
        private String fullName = "Marcia Gomes Yadav";
    }

    @Getter
    @Setter
    public static class Currency {
        private String symbol = "₹";
    }

    @Getter
    @Setter
    public static class Export {
        private String displayDateFormat = "dd MMM yyyy";
        private String generatedTimestampFormat = "dd MMM yyyy, hh:mm a";
        private String rowDateTimeFormat = "dd MMM, hh:mm a";
        private String csvDateFormat = "yyyy-MM-dd";
        private String csvDateTimeFormat = "yyyy-MM-dd HH:mm";
    }

    /**
     * Owner-password has no default on purpose — it must come from the HEALING_HOUSE_OWNER_PASSWORD
     * env var (see application.yml), in every profile including dev, so SecuritySeeder can fail
     * loudly at startup rather than seed a guessable literal (Security_RBAC_Requirements_v1.md §7, §11).
     */
    @Getter
    @Setter
    public static class Security {
        private String ownerUsername = "owner";
        private String ownerPassword;
        private int maxFailedLoginAttempts = 5;
        private int lockoutMinutes = 15;
    }
}
