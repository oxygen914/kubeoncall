package com.kubeoncall.notification.domain;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/** A platform-neutral action rendered as a link or, for capable providers, an interactive control. */
public record NotificationAction(String key, String label, URI url, boolean primary) {

    public NotificationAction {
        key = requireText(key, "action key");
        label = requireText(label, "action label");
        url = Objects.requireNonNull(url, "action url must not be null");
        String scheme = url.getScheme();
        if (!url.isAbsolute()
                || scheme == null
                || (!"http".equals(scheme.toLowerCase(Locale.ROOT))
                        && !"https".equals(scheme.toLowerCase(Locale.ROOT)))) {
            throw new IllegalArgumentException("action url must be an absolute HTTP(S) URL");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
