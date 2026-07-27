package com.kubeoncall.sandbox.policy;

import java.util.Optional;

import com.kubeoncall.sandbox.domain.SandboxRunMode;

/**
 * Server-owned lookup for executable sandbox tools.
 *
 * <p>The public API may reference a tool by id and version only. Image digests, entrypoints and
 * network posture are always resolved here, never supplied by the caller. SBX-15 supplies the
 * concrete fixed-tool catalog; keeping the boundary in the control plane now prevents the SBX-06
 * API from accidentally accepting arbitrary images while the catalog is still empty.
 */
public interface SandboxToolCatalog {

    Optional<SandboxToolSpec> find(String toolId, String toolVersion, SandboxRunMode mode);
}
