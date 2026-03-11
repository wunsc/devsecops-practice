// jenkins-shared-lib/src/com/devsecops/ImageTagger.groovy
// Generates image tags based on pipeline trigger type
// T2 (merge): main-<short-sha>
// T3 (tag):   <tag-name> (e.g., v1.2.0)
package com.devsecops

import com.cloudbees.groovy.cps.NonCPS

class ImageTagger implements Serializable {
    private static final long serialVersionUID = 1L

    /**
     * Generate tag for T2 merge pipeline
     * Format: main-<7-char-sha>
     * @param gitSha Full git commit SHA
     * @return String tag like "main-a1b2c3d"
     */
    @NonCPS
    static String forMerge(String gitSha) {
        def shortSha = gitSha?.take(7) ?: 'unknown'
        return "main-${shortSha}"
    }

    /**
     * Generate tag for T3 tag pipeline
     * Uses the git tag name directly (e.g., v1.2.0)
     * @param gitBranch The GIT_BRANCH env var (e.g., origin/tags/v1.2.0)
     * @return String tag like "v1.2.0"
     */
    @NonCPS
    static String forTag(String gitBranch) {
        if (!gitBranch) return 'unknown-tag'
        // Strip refs/tags/ or origin/tags/ prefix
        // KNOWN FIX: In pipelineJob (not multibranch), GIT_BRANCH may be
        // "origin/tags/v1.2.0" or "refs/tags/v1.2.0"
        def tag = gitBranch
            .replaceAll('.*/tags/', '')
            .replaceAll('.*/', '')
        return tag ?: 'unknown-tag'
    }

    /**
     * Validate a semantic version tag
     * @param tag Tag string (e.g., "v1.2.0")
     * @return true if valid semver format
     */
    @NonCPS
    static boolean isValidSemver(String tag) {
        return tag ==~ /^v?\d+\.\d+\.\d+(-[\w.]+)?$/
    }
}
