package com.kubeoncall.rag.runbook;

import com.kubeoncall.common.config.KubeOnCallProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class RunbookBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RunbookBootstrapRunner.class);

    private final KubeOnCallProperties properties;
    private final RunbookImportService importService;

    public RunbookBootstrapRunner(KubeOnCallProperties properties,
                                  RunbookImportService importService) {
        this.properties = properties;
        this.importService = importService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.getRag().isRunbookBootstrapEnabled()) {
            return;
        }
        RunbookImportService.ImportResult result = importService.importAll(false);
        log.info("Runbook bootstrap scanned={}, imported={}, skipped={}, failed={}",
                result.scanned(), result.imported(), result.skipped(), result.failed());
    }
}
