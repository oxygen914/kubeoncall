package com.kubeoncall.rag.runbook;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.kubeoncall.domain.rag.RetrievalRequest;
import com.kubeoncall.rag.KnowledgeIngestService;
import com.kubeoncall.rag.repository.KnowledgeRepository;

@Service
public class RunbookImportService {

    private final RunbookCatalog catalog;
    private final KnowledgeIngestService knowledgeIngestService;
    private final KnowledgeRepository knowledgeRepository;

    public RunbookImportService(
            RunbookCatalog catalog,
            KnowledgeIngestService knowledgeIngestService,
            KnowledgeRepository knowledgeRepository) {
        this.catalog = catalog;
        this.knowledgeIngestService = knowledgeIngestService;
        this.knowledgeRepository = knowledgeRepository;
    }

    public ImportResult importAll(boolean dryRun) {
        List<RunbookAsset> assets = catalog.load();
        List<AssetResult> results = new ArrayList<>();
        int eligible = 0;
        int imported = 0;
        int skipped = 0;
        int failed = 0;
        for (RunbookAsset asset : assets) {
            try {
                if (alreadyImported(asset)) {
                    skipped++;
                    results.add(new AssetResult(
                            asset.runbookId(), asset.resourceName(), "skipped", "version already imported"));
                    continue;
                }
                eligible++;
                if (dryRun) {
                    results.add(new AssetResult(asset.runbookId(), asset.resourceName(), "eligible", "dry-run"));
                    continue;
                }
                knowledgeIngestService.ingest(asset.title(), asset.content(), "runbook", asset.metadata());
                imported++;
                results.add(new AssetResult(asset.runbookId(), asset.resourceName(), "imported", "success"));
            } catch (RuntimeException ex) {
                failed++;
                results.add(new AssetResult(asset.runbookId(), asset.resourceName(), "failed", "Import failed"));
            }
        }
        return new ImportResult(assets.size(), eligible, imported, skipped, failed, dryRun, List.copyOf(results));
    }

    private boolean alreadyImported(RunbookAsset asset) {
        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("runbookId", asset.runbookId());
        filters.put("dataset_version", asset.metadata().get("dataset_version"));
        filters.put("chunk_enable", "true");
        return !knowledgeRepository
                .searchLexical(new RetrievalRequest("", filters, 1), 1)
                .isEmpty();
    }

    public record ImportResult(
            int scanned,
            int eligible,
            int imported,
            int skipped,
            int failed,
            boolean dryRun,
            List<AssetResult> assets) {}

    public record AssetResult(String runbookId, String resourceName, String status, String message) {}
}
