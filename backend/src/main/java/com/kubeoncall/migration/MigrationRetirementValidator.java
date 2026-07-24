package com.kubeoncall.migration;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.kubeoncall.common.config.DataMigrationProperties.DomainRetirement;
import com.kubeoncall.common.config.KubeOnCallProperties;

/**
 * WBS-11 GAP-11-02: warns at startup when a non-isomorphic domain claims MySQL as its fact source
 * but has not yet disabled the Redis compatibility write. That combination leaves a domain in a
 * logically inconsistent cutover state — MySQL is authoritative yet Redis is still being written.
 * The warning is advisory only; it never blocks startup so a misconfiguration cannot take the app
 * down.
 */
@Component
public class MigrationRetirementValidator {

    private static final Logger log = LoggerFactory.getLogger(MigrationRetirementValidator.class);

    private final KubeOnCallProperties properties;

    public MigrationRetirementValidator(KubeOnCallProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void validate() {
        check("approval", properties.getDataMigration().getApproval());
        check("skill-state", properties.getDataMigration().getSkillState());
        check("execution-audit", properties.getDataMigration().getExecutionAudit());
    }

    private void check(String domain, DomainRetirement retirement) {
        if ("MYSQL".equalsIgnoreCase(retirement.factSource()) && !retirement.legacyWriteDisabled()) {
            log.warn(
                    "Migration domain '{}' declares factSource=MYSQL but legacyWriteDisabled=false; "
                            + "Redis compatibility writes are still active while MySQL is claimed authoritative. "
                            + "Set legacy-write-disabled=true to complete the cutover.",
                    domain);
        }
    }
}
