package com.kubeoncall.sandbox;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Cheap, deterministic admission checks for Agent-produced code.
 *
 * <p>This is intentionally not a sandbox. The Controller-enforced Job deadline, CPU/memory quota,
 * PID ceiling, read-only filesystem, absent ServiceAccount token and default-deny NetworkPolicy are
 * the security boundary. These checks only reject obviously unsafe or non-portable submissions
 * before an expensive sandbox Job is created.
 */
public final class GeneratedCodeAdmission {

    private static final Pattern PATH_TRAVERSAL = Pattern.compile("(?:^|[\\s'\\\"])(?:\\.\\./|/\\.\\.)");
    private static final Pattern NETWORK =
            Pattern.compile("(?i)\\b(?:curl|wget|nc|netcat|socket|requests|urllib|http\\.client)\\b");
    private static final Pattern FORK_BOMB = Pattern.compile("(?s):\\s*\\(\\s*\\)\\s*\\{\\s*:\\s*\\|\\s*:\\s*&");
    private static final Pattern INFINITE_LOOP =
            Pattern.compile("(?im)\\bwhile\\s+(?:true|1)\\b|\\bwhile\\s*:\\s*$|for\\s*\\(\\s*;\\s*;\\s*\\)");

    private GeneratedCodeAdmission() {}

    /** Returns a stable list of early-rejection reasons; callers must not treat an empty result as safe execution. */
    public static List<String> inspect(String source) {
        if (source == null || source.isBlank()) {
            return List.of("EMPTY_SOURCE");
        }
        String text = source.toLowerCase(Locale.ROOT);
        java.util.ArrayList<String> findings = new java.util.ArrayList<>();
        if (source.indexOf('\u0000') >= 0) {
            findings.add("NUL_BYTE");
        }
        if (PATH_TRAVERSAL.matcher(source).find()) {
            findings.add("PATH_TRAVERSAL");
        }
        if (NETWORK.matcher(text).find()) {
            findings.add("NETWORK_ATTEMPT");
        }
        if (FORK_BOMB.matcher(source).find()) {
            findings.add("FORK_BOMB_SIGNATURE");
        }
        if (INFINITE_LOOP.matcher(source).find()) {
            findings.add("INFINITE_LOOP_SIGNATURE");
        }
        return List.copyOf(findings);
    }
}
