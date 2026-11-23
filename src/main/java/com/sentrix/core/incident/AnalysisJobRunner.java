package com.sentrix.core.incident;

import com.sentrix.core.analysis.AnalysisModelClient;
import com.sentrix.core.analysis.AnalysisModelSettings;
import com.sentrix.core.analysis.AnalysisProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@EnableScheduling
public class AnalysisJobRunner {
    private static final Logger log = LoggerFactory.getLogger(AnalysisJobRunner.class);
    private final AnalysisJobs jobs;
    private final AnalysisModelSettings settings;
    private final AnalysisModelClient client;
    private final ObjectMapper mapper;
    private final boolean workerEnabled;
    AnalysisJobRunner(AnalysisJobs jobs, AnalysisModelSettings settings, AnalysisModelClient client, ObjectMapper mapper,
                      @Value("${sentrix.analysis.worker-enabled:true}") boolean workerEnabled) {
        this.jobs=jobs; this.settings=settings; this.client=client; this.mapper=mapper; this.workerEnabled=workerEnabled;
    }
    @Scheduled(fixedDelay=1000, initialDelay=1000)
    public void tick() {
        if (workerEnabled) {
            jobs.enqueueDue(java.time.Instant.now());
            runNext();
        }
    }

    public void runNext() {
        jobs.recoverExpired();
        if (!settings.enabled()) { jobs.blockQueued(); return; }
        var claim = jobs.claimNext();
        if (claim == null) return;
        AnalysisProtocol.Response result = null;
        String failure = "INVALID_ANALYSIS_REQUEST";
        try {
            var request = mapper.readValue(claim.requestJson(),AnalysisProtocol.Request.class);
            if (!settings.modelVersion().equals(request.modelVersion()) || !settings.featureSchemaVersion().equals(request.featureSchemaVersion())
                    || !AnalysisProtocol.CONTRACT_VERSION.equals(request.contractVersion())) {
                failure = "MODEL_CONFIGURATION_CHANGED";
            } else {
                result = client.analyze(request);
            }
        } catch (AnalysisModelClient.AnalysisFailure e) {
            failure = e.code();
        } catch (RuntimeException e) {
            // Do not log raw model bodies, endpoints or observation data.
            log.warn("Analysis job {} failed ({})",claim.jobId(),e.getClass().getSimpleName());
        }
        jobs.finish(claim,result,failure);
    }
}
