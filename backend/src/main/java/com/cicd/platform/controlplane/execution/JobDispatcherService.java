package com.cicd.platform.controlplane.execution;

import com.cicd.platform.controlplane.execution.config.ExecutionConstants;
import com.cicd.platform.controlplane.execution.config.WorkspaceConfig;
import com.cicd.platform.controlplane.execution.message.JobDispatchMessage;
import com.cicd.platform.controlplane.domain.entity.PipelineJob;
import com.cicd.platform.controlplane.domain.entity.PipelineStage;
import com.cicd.platform.controlplane.domain.entity.PipelineRun;
import com.cicd.platform.controlplane.domain.entity.PipelineVersion;
import com.cicd.platform.controlplane.domain.entity.JobAttempt;
import com.cicd.platform.controlplane.domain.repository.PipelineJobRepository;
import com.cicd.platform.controlplane.domain.repository.PipelineStageRepository;
import com.cicd.platform.controlplane.domain.repository.PipelineRunRepository;
import com.cicd.platform.controlplane.domain.repository.JobAttemptRepository;
import com.cicd.platform.controlplane.pipeline.PipelineConfigMapper;
import com.cicd.platform.controlplane.pipeline.PipelineConfigMapper.JobDefinition;
import com.cicd.platform.controlplane.pipeline.PipelineConfigMapper.StageDefinition;
import com.cicd.platform.controlplane.pipeline.config.PipelineConfig;
import com.cicd.platform.controlplane.pipeline.parser.PipelineYamlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class JobDispatcherService {

    private static final Logger log = LoggerFactory.getLogger(JobDispatcherService.class);

    private final RabbitTemplate rabbitTemplate;
    private final PipelineRunRepository pipelineRunRepository;
    private final PipelineStageRepository pipelineStageRepository;
    private final PipelineJobRepository pipelineJobRepository;
    private final JobAttemptRepository jobAttemptRepository;
    private final WorkspaceConfig workspaceConfig;
    private final PipelineYamlParser pipelineYamlParser = new PipelineYamlParser();
    private final PipelineConfigMapper pipelineConfigMapper = new PipelineConfigMapper();

    public JobDispatcherService(
            RabbitTemplate rabbitTemplate,
            PipelineRunRepository pipelineRunRepository,
            PipelineStageRepository pipelineStageRepository,
            PipelineJobRepository pipelineJobRepository,
            JobAttemptRepository jobAttemptRepository,
            WorkspaceConfig workspaceConfig) {
        this.rabbitTemplate = rabbitTemplate;
        this.pipelineRunRepository = pipelineRunRepository;
        this.pipelineStageRepository = pipelineStageRepository;
        this.pipelineJobRepository = pipelineJobRepository;
        this.jobAttemptRepository = jobAttemptRepository;
        this.workspaceConfig = workspaceConfig;
    }

    @Transactional
    public void dispatchReadyJobs(UUID runId) {
        log.info("Dispatching ready jobs for run: {}", runId);

        PipelineRun run = pipelineRunRepository.findById(runId).orElse(null);
        if (run == null || run.getStatus() != PipelineRun.RunStatus.RUNNING) {
            return;
        }

        List<PipelineStage> stages = pipelineStageRepository.findByPipelineRunIdOrderByOrderIndexAsc(runId);

        Map<String, List<String>> stageDependencies = buildDependencyMap(run.getPipelineVersion());
        Map<String, Map<String, List<String>>> jobDependencies = buildJobDependencyMap(run.getPipelineVersion());

        for (PipelineStage stage : stages) {
            if (stage.getStatus() == PipelineStage.StageStatus.SUCCESS
                    || stage.getStatus() == PipelineStage.StageStatus.SKIPPED) {
                continue;
            }

            String stageName = stage.getName() != null ? stage.getName().toLowerCase() : "";
            List<String> declaredDeps = stageDependencies.getOrDefault(stageName, List.of());

            boolean depsReady;
            if (!declaredDeps.isEmpty()) {
                depsReady = areDeclaredDepsMet(declaredDeps, stages);
            } else {
                depsReady = areAllPreviousStagesSuccess(stage, stages);
            }

            if (!depsReady) {
                continue;
            }

            if (stage.getStatus() == PipelineStage.StageStatus.FAILED) {
                continue;
            }

            List<PipelineJob> jobs = pipelineJobRepository.findByPipelineStageId(stage.getId());
            Map<String, List<String>> jobDeps = jobDependencies.getOrDefault(stageName, Map.of());
            for (PipelineJob job : jobs) {
                if (job.getStatus() == PipelineJob.JobStatus.PENDING) {
                    List<String> declaredJobDeps = jobDeps.getOrDefault(
                            job.getName() != null ? job.getName().toLowerCase() : "", List.of());
                    if (!declaredJobDeps.isEmpty() && !areJobDepsMet(declaredJobDeps, jobs)) {
                        continue;
                    }
                    dispatchJob(job);
                }
            }
        }
    }

    private boolean areDeclaredDepsMet(List<String> declaredDeps, List<PipelineStage> stages) {
        for (String depName : declaredDeps) {
            boolean depFound = false;
            for (PipelineStage s : stages) {
                if (s.getName() != null && s.getName().toLowerCase().equals(depName)) {
                    if (s.getStatus() != PipelineStage.StageStatus.SUCCESS) {
                        return false;
                    }
                    depFound = true;
                    break;
                }
            }
            if (!depFound) {
                return false;
            }
        }
        return true;
    }

    private boolean areJobDepsMet(List<String> declaredDeps, List<PipelineJob> jobs) {
        for (String depName : declaredDeps) {
            boolean depFound = false;
            for (PipelineJob job : jobs) {
                if (job.getName() != null && job.getName().toLowerCase().equals(depName)) {
                    if (job.getStatus() != PipelineJob.JobStatus.SUCCESS) {
                        return false;
                    }
                    depFound = true;
                    break;
                }
            }
            if (!depFound) {
                return false;
            }
        }
        return true;
    }

    private boolean areAllPreviousStagesSuccess(PipelineStage stage, List<PipelineStage> stages) {
        for (PipelineStage prevStage : stages) {
            if (prevStage.getOrderIndex() >= stage.getOrderIndex()) {
                break;
            }
            if (prevStage.getStatus() != PipelineStage.StageStatus.SUCCESS) {
                return false;
            }
        }
        return true;
    }

    private Map<String, List<String>> buildDependencyMap(PipelineVersion version) {
        if (version == null || version.getYamlContent() == null || version.getYamlContent().isBlank()) {
            return Map.of();
        }
        try {
            PipelineConfig config = pipelineYamlParser.parse(version.getYamlContent());
            List<StageDefinition> stageDefs = pipelineConfigMapper.toStageDefinitions(config);
            Map<String, List<String>> map = new HashMap<>();
            for (StageDefinition sd : stageDefs) {
                map.put(sd.name().toLowerCase(),
                        sd.dependsOn() != null
                                ? sd.dependsOn().stream().map(String::toLowerCase).toList()
                                : List.of());
            }
            return map;
        } catch (Exception e) {
            log.debug("Could not parse YAML for dependency info, using positional ordering: {}",
                    e.getMessage());
            return Map.of();
        }
    }

    private Map<String, Map<String, List<String>>> buildJobDependencyMap(PipelineVersion version) {
        if (version == null || version.getYamlContent() == null || version.getYamlContent().isBlank()) {
            return Map.of();
        }
        try {
            PipelineConfig config = pipelineYamlParser.parse(version.getYamlContent());
            List<StageDefinition> stageDefs = pipelineConfigMapper.toStageDefinitions(config);
            Map<String, Map<String, List<String>>> map = new HashMap<>();
            for (StageDefinition sd : stageDefs) {
                Map<String, List<String>> jobMap = new HashMap<>();
                for (JobDefinition jd : sd.jobs()) {
                    jobMap.put(jd.name().toLowerCase(),
                            jd.dependsOn() != null
                                    ? jd.dependsOn().stream().map(String::toLowerCase).toList()
                                    : List.of());
                }
                map.put(sd.name().toLowerCase(), jobMap);
            }
            return map;
        } catch (Exception e) {
            log.debug("Could not parse YAML for job dependency info: {}", e.getMessage());
            return Map.of();
        }
    }

    public void dispatchJob(PipelineJob job) {
        log.info("Dispatching job: {} ({})", job.getName(), job.getId());

        job.setStatus(PipelineJob.JobStatus.QUEUED);
        pipelineJobRepository.save(job);

        int attemptNumber = computeAttemptNumber(job);
        JobAttempt attempt = new JobAttempt(job, attemptNumber);
        attempt.setStatus(JobAttempt.AttemptStatus.PENDING);
        attempt.setStartedAt(Instant.now());
        jobAttemptRepository.save(attempt);

        JobDispatchMessage message = buildDispatchMessage(job, attemptNumber);
        sendDispatchMessage(message);

        log.info("Dispatched job {} to exchange (attempt {})", job.getId(), attemptNumber);
    }

    @Transactional
    public void dispatchForRetry(PipelineJob job, int attemptNumber) {
        log.info("Dispatching retry for job: {}, attempt: {}", job.getId(), attemptNumber);

        job.setStatus(PipelineJob.JobStatus.QUEUED);
        pipelineJobRepository.save(job);

        JobAttempt attempt = new JobAttempt(job, attemptNumber);
        attempt.setStatus(JobAttempt.AttemptStatus.PENDING);
        attempt.setStartedAt(Instant.now());
        jobAttemptRepository.save(attempt);

        JobDispatchMessage message = buildDispatchMessage(job, attemptNumber);
        sendDispatchMessage(message);

        log.info("Dispatched retry for job {} to exchange (attempt {})", job.getId(), attemptNumber);
    }

    private int computeAttemptNumber(PipelineJob job) {
        List<JobAttempt> previousAttempts = jobAttemptRepository.findByJobIdOrderByAttemptNumberAsc(job.getId());
        if (previousAttempts.isEmpty()) {
            return 1;
        }
        return previousAttempts.get(previousAttempts.size() - 1).getAttemptNumber() + 1;
    }

    private JobDispatchMessage buildDispatchMessage(PipelineJob job, int attemptNumber) {
        PipelineStage stage = job.getPipelineStage();
        PipelineRun run = stage.getPipelineRun();
        PipelineVersion version = run.getPipelineVersion();

        String gitUrl = "";
        if (run.getRepository() != null) {
            gitUrl = run.getRepository().getRepositoryUrl();
        }

        return JobDispatchMessage.create(
                job.getId(),
                run.getId(),
                version.getId(),
                job.getName(),
                job.getJobType().name(),
                gitUrl,
                run.getBranch(),
                run.getCommitSha(),
                attemptNumber
        );
    }

    private void sendDispatchMessage(JobDispatchMessage message) {
        rabbitTemplate.convertAndSend(
                ExecutionConstants.JOB_DISPATCH_EXCHANGE,
                ExecutionConstants.JOB_DISPATCH_ROUTING_KEY,
                message);
    }
}
