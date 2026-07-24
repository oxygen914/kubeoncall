package com.kubeoncall.migration;

import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

import com.kubeoncall.common.config.DataMigrationProperties;
import com.kubeoncall.common.config.KubeOnCallProperties;

class MigrationRetirementValidatorTest {

    @Test
    void doesNotFailWhenDomainsAreConsistent() {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        // Defaults: every domain is REDIS + legacyWriteDisabled=false, which is consistent.
        assertThatCode(() -> new MigrationRetirementValidator(properties).validate())
                .doesNotThrowAnyException();
    }

    @Test
    void warnsButDoesNotFailWhenMysqlFactSourceStillWritesRedis() {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getDataMigration().setApproval(new DataMigrationProperties.DomainRetirement("MYSQL", false));
        // The validator only logs; it must never block startup even in an inconsistent state.
        assertThatCode(() -> new MigrationRetirementValidator(properties).validate())
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsFullMysqlCutover() {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getDataMigration().setApproval(new DataMigrationProperties.DomainRetirement("MYSQL", true));
        properties.getDataMigration().setSkillState(new DataMigrationProperties.DomainRetirement("MYSQL", true));
        properties.getDataMigration().setExecutionAudit(new DataMigrationProperties.DomainRetirement("MYSQL", true));
        assertThatCode(() -> new MigrationRetirementValidator(properties).validate())
                .doesNotThrowAnyException();
    }
}
