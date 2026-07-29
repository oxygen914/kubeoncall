package com.kubeoncall.knowledge;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Converts supported uploaded documents into the canonical one-line JSONL format consumed by the
 * durable knowledge import worker. The original import type remains on the import record and on
 * the resulting document metadata.
 */
@Service
public class KnowledgeImportContentNormalizer {

    private static final Set<String> DOCUMENT_SUFFIXES = Set.of(
            "csv",
            "doc",
            "docx",
            "htm",
            "html",
            "json",
            "log",
            "markdown",
            "md",
            "odt",
            "pdf",
            "rtf",
            "text",
            "txt",
            "yaml",
            "yml");
    private static final int MAX_EXTRACTED_CHARACTERS = 2_000_000;

    private final ObjectMapper objectMapper;
    private final Tika tika;

    @Autowired
    public KnowledgeImportContentNormalizer(ObjectMapper objectMapper) {
        this(objectMapper, new Tika());
    }

    KnowledgeImportContentNormalizer(ObjectMapper objectMapper, Tika tika) {
        this.objectMapper = objectMapper;
        this.tika = tika;
    }

    public NormalizedImport normalize(
            String requestedType, String originalFilename, String contentType, byte[] source) {
        ImportType importType = ImportType.parse(requestedType);
        byte[] content = source == null ? new byte[0] : source.clone();
        if (content.length == 0) {
            throw new IllegalArgumentException("A non-empty knowledge file is required");
        }
        String filename = safeFilename(originalFilename);
        if (importType == ImportType.JSONL) {
            requireJsonlFilename(filename, contentType);
            return new NormalizedImport(importType.name(), filename, content);
        }
        requireDocumentFilename(filename);
        String extracted = extract(content, filename).trim();
        if (extracted.isBlank()) {
            throw new IllegalArgumentException("The uploaded document does not contain extractable text");
        }
        try {
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("doc_id", sha256(importType.name() + ":" + filename + ":" + sha256(content)));
            document.put("title", titleFromFilename(filename));
            document.put("content", extracted);
            document.put("document_type", importType == ImportType.RUNBOOK ? "RUNBOOK" : "DOCUMENT");
            document.put("source_type", importType.name());
            document.put(
                    "metadata",
                    Map.of(
                            "originalFilename",
                            filename,
                            "originalContentType",
                            contentType == null ? "" : contentType,
                            "importType",
                            importType.name()));
            return new NormalizedImport(
                    importType.name(),
                    filename,
                    (objectMapper.writeValueAsString(document) + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalArgumentException("The uploaded document cannot be normalized for knowledge import", ex);
        }
    }

    private String extract(byte[] content, String filename) {
        try (ByteArrayInputStream input = new ByteArrayInputStream(content)) {
            return tika.parseToString(input, new Metadata(), MAX_EXTRACTED_CHARACTERS);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Unable to extract text from " + filename, ex);
        }
    }

    private static void requireJsonlFilename(String filename, String contentType) {
        if (filename.endsWith(".jsonl")
                || filename.endsWith(".ndjson")
                || "application/x-ndjson".equalsIgnoreCase(contentType)) {
            return;
        }
        throw new IllegalArgumentException("JSONL import requires a .jsonl or .ndjson file");
    }

    private static void requireDocumentFilename(String filename) {
        int separator = filename.lastIndexOf('.');
        String suffix = separator < 0 ? "" : filename.substring(separator + 1).toLowerCase(Locale.ROOT);
        if (!DOCUMENT_SUFFIXES.contains(suffix)) {
            throw new IllegalArgumentException(
                    "Unsupported document format. Supported formats: md, txt, pdf, doc, docx, odt, rtf, html, csv, json, yaml");
        }
    }

    private static String safeFilename(String value) {
        String filename = value == null ? "" : value.trim();
        if (filename.isBlank()) {
            throw new IllegalArgumentException("Uploaded file must have a filename");
        }
        return filename.replace('\\', '/').substring(filename.replace('\\', '/').lastIndexOf('/') + 1);
    }

    private static String titleFromFilename(String filename) {
        int separator = filename.lastIndexOf('.');
        String title = separator > 0 ? filename.substring(0, separator) : filename;
        return title.isBlank() ? "Knowledge document" : title;
    }

    private static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to hash knowledge import", ex);
        }
    }

    public record NormalizedImport(String importType, String originalFilename, byte[] jsonlContent) {
        public NormalizedImport {
            jsonlContent = jsonlContent == null ? null : jsonlContent.clone();
        }

        @Override
        public byte[] jsonlContent() {
            return jsonlContent == null ? null : jsonlContent.clone();
        }
    }

    enum ImportType {
        DOCUMENT,
        JSONL,
        RUNBOOK;

        static ImportType parse(String value) {
            try {
                return ImportType.valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("importType must be DOCUMENT, JSONL or RUNBOOK");
            }
        }
    }
}
