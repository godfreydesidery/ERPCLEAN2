package com.erp.modules.notifications.service;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Substitutes {@code {placeholder}} tokens in title/body/link templates (ADR-0024 D-5).
 *
 * <p>A missing placeholder renders as empty string and logs WARN — a template/values mismatch
 * is a config defect, not a runtime failure (D-5). No {@code Money}, no arithmetic — all values
 * are already-formatted strings in the {@code templateValues} map (BR-NOTIF-13).
 */
@Component
public class TemplateRenderer {

    private static final Logger log = LoggerFactory.getLogger(TemplateRenderer.class);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");

    public String render(String template, Map<String, String> values) {
        if (template == null) return "";
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String val = values.getOrDefault(key, null);
            if (val == null) {
                log.warn("TemplateRenderer: placeholder '{}' not found in templateValues for template: {}",
                         key, template);
                val = "";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(val));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
