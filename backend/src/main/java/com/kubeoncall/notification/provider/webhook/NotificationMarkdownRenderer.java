package com.kubeoncall.notification.provider.webhook;

import java.util.Map;

import com.kubeoncall.notification.domain.NotificationAction;
import com.kubeoncall.notification.domain.NotificationMessage;
import com.kubeoncall.observability.SensitiveDataRedactor;

/** Bounded, redacted markdown shared by one-way group webhook providers. */
public final class NotificationMarkdownRenderer {

    private static final int MAX_FACTS = 12;
    private static final int MAX_FIELD_LENGTH = 300;
    private static final int MAX_MARKDOWN_LENGTH = 12_000;
    private static final SensitiveDataRedactor REDACTOR = SensitiveDataRedactor.STANDARD;

    private NotificationMarkdownRenderer() {}

    public static String render(NotificationMessage message) {
        return render(message, true);
    }

    public static String render(NotificationMessage message, boolean includeActions) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("**").append(safe(message.summary())).append("**");
        int count = 0;
        for (Map.Entry<String, String> fact : message.facts().entrySet()) {
            if (count++ >= MAX_FACTS) {
                markdown.append("\n- 其他信息：已省略");
                break;
            }
            markdown.append("\n- ").append(safe(fact.getKey())).append("：").append(safe(fact.getValue()));
        }
        if (includeActions) {
            for (NotificationAction action : message.actions()) {
                markdown.append("\n- [")
                        .append(safe(action.label()))
                        .append("](")
                        .append(action.url())
                        .append(')');
            }
        }
        String rendered = markdown.toString();
        return rendered.length() <= MAX_MARKDOWN_LENGTH
                ? rendered
                : rendered.substring(0, MAX_MARKDOWN_LENGTH) + "\n…已截断";
    }

    public static String safe(String value) {
        String redacted = REDACTOR.redactText(value == null ? "" : value);
        String escaped = redacted.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return escaped.length() <= MAX_FIELD_LENGTH ? escaped : escaped.substring(0, MAX_FIELD_LENGTH) + "…";
    }
}
