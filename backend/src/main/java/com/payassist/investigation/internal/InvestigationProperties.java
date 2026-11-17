package com.payassist.investigation.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Synthetic thresholds for time-based investigation findings (not real scheme rules).
 *
 * @param pendingAlertAfter        a PENDING payment older than this is flagged
 * @param authorizedAlertAfter     an AUTHORIZED, uncaptured payment older than this is flagged
 *                                 (card authorizations typically lapse after about 7 days)
 * @param settlementDelayAfter     a CAPTURED payment not settled after this is flagged
 */
@ConfigurationProperties("payassist.investigation")
public record InvestigationProperties(
        @DefaultValue("24h") Duration pendingAlertAfter,
        @DefaultValue("7d") Duration authorizedAlertAfter,
        @DefaultValue("3d") Duration settlementDelayAfter) {

    public InvestigationProperties {
        for (Duration d : new Duration[] {pendingAlertAfter, authorizedAlertAfter, settlementDelayAfter}) {
            if (d == null || d.isNegative() || d.isZero()) {
                throw new IllegalArgumentException("payassist.investigation thresholds must be positive");
            }
        }
    }

    public static InvestigationProperties defaults() {
        return new InvestigationProperties(Duration.ofHours(24), Duration.ofDays(7), Duration.ofDays(3));
    }
}
