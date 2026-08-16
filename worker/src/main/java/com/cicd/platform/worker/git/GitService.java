package com.cicd.platform.worker.git;

import com.cicd.platform.worker.domain.PipelineJob;

import java.nio.file.Path;

/**
 * Abstraction over git operations used to materialise the exact commit a job
 * must build.
 *
 * <p>The worker MUST build the exact {@code commitSha} from the job; it never
 * builds "latest". The flow is: clone → fetch → verify commit exists →
 * checkout exact SHA → verify HEAD.</p>
 */
public interface GitService {

    /**
     * Clones the repository into {@code targetDir} and checks out the exact
     * commit requested by the job.
     *
     * @param job       the validated pipeline job.
     * @param targetDir empty local directory that becomes the checkout.
     * @return resolved commit information.
     */
    CommitInfo checkoutCommit(PipelineJob job, Path targetDir);
}
