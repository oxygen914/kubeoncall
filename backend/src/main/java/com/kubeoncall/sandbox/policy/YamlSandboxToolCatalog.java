package com.kubeoncall.sandbox.policy;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.kubeoncall.sandbox.domain.SandboxRunMode;

/** Loads the immutable, versioned server-owned sandbox tool catalog bundled with the backend. */
@Component
public class YamlSandboxToolCatalog implements SandboxToolCatalog {

    private final Map<String, SandboxToolSpec> tools;

    public YamlSandboxToolCatalog() {
        try (InputStream input = new ClassPathResource("sandbox-tools/tools.yaml").getInputStream()) {
            this.tools = load(input, YamlSandboxToolCatalog::schemaExists);
        } catch (Exception ex) {
            throw new IllegalStateException("cannot load sandbox tool catalog", ex);
        }
    }

    /** Package-visible constructor keeps malformed catalog contracts directly testable. */
    YamlSandboxToolCatalog(InputStream input, Predicate<String> schemaResolver) {
        this.tools = load(input, schemaResolver);
    }

    @Override
    public Optional<SandboxToolSpec> find(String toolId, String toolVersion, SandboxRunMode mode) {
        if (toolId == null || toolVersion == null || mode == null) {
            return Optional.empty();
        }
        SandboxToolSpec spec = tools.get(key(toolId, toolVersion));
        return spec != null && spec.mode() == mode ? Optional.of(spec) : Optional.empty();
    }

    private static Map<String, SandboxToolSpec> load(InputStream input, Predicate<String> schemaResolver) {
        try {
            CatalogDocument document = new ObjectMapper(new YAMLFactory()).readValue(input, CatalogDocument.class);
            Map<String, SandboxToolSpec> loaded = new LinkedHashMap<>();
            for (ToolDocument tool : document.tools() == null ? List.<ToolDocument>of() : document.tools()) {
                SandboxToolSpec spec = new SandboxToolSpec(
                        tool.id(),
                        tool.version(),
                        SandboxRunMode.valueOf(tool.mode().trim().toUpperCase(Locale.ROOT)),
                        tool.imageDigest(),
                        tool.entrypoint(),
                        new SandboxResourceLimits(
                                tool.resources().cpu(),
                                tool.resources().memory(),
                                tool.resources().ephemeralStorage(),
                                tool.resources().inputMaxBytes(),
                                tool.resources().outputMaxBytes(),
                                tool.resources().logMaxBytes(),
                                tool.resources().scriptMaxBytes(),
                                tool.resources().timeoutSeconds(),
                                tool.resources().maxTimeoutSeconds()),
                        SandboxToolSpec.NetworkEgressPolicy.valueOf(
                                tool.networkEgressPolicy().trim().toUpperCase(Locale.ROOT)),
                        tool.inputSchemaRef(),
                        tool.outputSchemaRef());
                if (!sha256Digest(spec.imageDigest())) {
                    throw new IllegalStateException("sandbox tool image must contain a 64-character SHA-256 digest");
                }
                if (spec.mode() == SandboxRunMode.GENERATED_CODE
                        && spec.networkEgressPolicy() != SandboxToolSpec.NetworkEgressPolicy.DENY_ALL) {
                    throw new IllegalStateException("generated-code runtimes must default to DENY_ALL network egress");
                }
                if (!schemaResolver.test(spec.inputSchemaRef()) || !schemaResolver.test(spec.outputSchemaRef())) {
                    throw new IllegalStateException("sandbox tool schema reference is missing");
                }
                if (loaded.putIfAbsent(key(spec.id(), spec.version()), spec) != null) {
                    throw new IllegalStateException("duplicate sandbox tool " + spec.id() + ":" + spec.version());
                }
            }
            if (loaded.size() < 3) {
                throw new IllegalStateException("sandbox catalog must provide the first fixed diagnostic tools");
            }
            return Map.copyOf(loaded);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("cannot load sandbox tool catalog", ex);
        }
    }

    private static String key(String id, String version) {
        return id.trim() + ":" + version.trim();
    }

    private static boolean sha256Digest(String image) {
        int offset = image == null ? -1 : image.lastIndexOf("@sha256:");
        return offset >= 0 && image.substring(offset + "@sha256:".length()).matches("[0-9a-f]{64}");
    }

    private static boolean schemaExists(String path) {
        return path != null && path.startsWith("sandbox-tools/") && new ClassPathResource(path).exists();
    }

    private record CatalogDocument(List<ToolDocument> tools) {}

    private record ToolDocument(
            String id,
            String version,
            String mode,
            String imageDigest,
            String entrypoint,
            String networkEgressPolicy,
            String inputSchemaRef,
            String outputSchemaRef,
            ResourceDocument resources) {}

    private record ResourceDocument(
            String cpu,
            String memory,
            String ephemeralStorage,
            long inputMaxBytes,
            long outputMaxBytes,
            long logMaxBytes,
            long scriptMaxBytes,
            int timeoutSeconds,
            int maxTimeoutSeconds) {}
}
