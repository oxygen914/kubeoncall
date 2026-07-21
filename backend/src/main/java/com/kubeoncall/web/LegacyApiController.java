package com.kubeoncall.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Marks a controller as part of the legacy {@code /api/*} surface (WBS-11 retirement). The bean is
 * only registered when {@code kubeoncall.legacy-api.enabled=true} (default), so flipping that flag
 * to false removes the legacy endpoints without code changes — the v1 surface becomes the only API.
 * Apply alongside {@code @RestController}; this annotation only carries the condition, it does not
 * register a component. Integration webhook controllers are NOT annotated with this and stay
 * registered regardless, since they are system-to-system ingestion endpoints on their own auth
 * domain, not the old Console API.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(prefix = "kubeoncall.legacy-api", name = "enabled", havingValue = "true", matchIfMissing = true)
public @interface LegacyApiController {}
