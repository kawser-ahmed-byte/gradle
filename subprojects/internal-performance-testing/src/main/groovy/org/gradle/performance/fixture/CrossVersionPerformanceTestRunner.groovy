/*
 * Copyright 2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.performance.fixture

import org.gradle.api.JavaVersion
import org.gradle.integtests.fixtures.executer.GradleDistribution
import org.gradle.integtests.fixtures.executer.IntegrationTestBuildContext
import org.gradle.integtests.fixtures.versions.ReleasedVersionDistributions
import org.gradle.internal.jvm.Jvm
import org.gradle.internal.os.OperatingSystem
import org.gradle.performance.measure.Amount
import org.gradle.performance.measure.DataAmount
import org.gradle.performance.measure.Duration
import org.gradle.performance.results.CrossVersionPerformanceResults
import org.gradle.performance.results.DataReporter
import org.gradle.performance.results.MeasuredOperationList
import org.gradle.performance.results.ResultsStore
import org.gradle.util.GradleVersion
import org.junit.Assume

public class CrossVersionPerformanceTestRunner extends PerformanceTestSpec {
    GradleDistribution current
    final IntegrationTestBuildContext buildContext = new IntegrationTestBuildContext()
    final DataReporter<CrossVersionPerformanceResults> reporter
    TestProjectLocator testProjectLocator = new TestProjectLocator()
    final BuildExperimentRunner experimentRunner
    final ReleasedVersionDistributions releases

    String testProject
    File workingDir
    boolean useDaemon

    List<String> tasksToRun = []
    List<String> args = []
    List<String> gradleOpts = ['-Xms2g', '-Xmx2g']
    List<String> previousTestIds = []
    int maxPermSizeMB = 256

    List<String> targetVersions = []
    Amount<Duration> maxExecutionTimeRegression = Duration.millis(0)
    Amount<DataAmount> maxMemoryRegression = DataAmount.bytes(0)

    BuildExperimentListener buildExperimentListener
    private boolean adhocRun

    CrossVersionPerformanceTestRunner(BuildExperimentRunner experimentRunner, DataReporter<CrossVersionPerformanceResults> reporter, ReleasedVersionDistributions releases, boolean adhocRun) {
        this.adhocRun = adhocRun
        this.reporter = reporter
        this.experimentRunner = experimentRunner
        this.releases = releases
    }

    CrossVersionPerformanceResults run() {
        if (testId == null) {
            throw new IllegalStateException("Test id has not been specified")
        }
        if (testProject == null) {
            throw new IllegalStateException("Test project has not been specified")
        }
        if (workingDir == null) {
            throw new IllegalStateException("Working directory has not been specified")
        }
        if (!targetVersions) {
            throw new IllegalStateException("Target versions have not been specified")
        }

        def scenarioSelector = new TestScenarioSelector()
        Assume.assumeTrue(scenarioSelector.shouldRun(testId, [testProject].toSet(), (ResultsStore) reporter))

        def results = new CrossVersionPerformanceResults(
            testId: testId,
            previousTestIds: previousTestIds.collect { it.toString() }, // Convert GString instances
            testProject: testProject,
            tasks: tasksToRun.collect { it.toString() },
            args: args.collect { it.toString() },
            gradleOpts: resolveGradleOpts().collect { it.toString() },
            daemon: useDaemon,
            jvm: Jvm.current().toString(),
            operatingSystem: OperatingSystem.current().toString(),
            versionUnderTest: GradleVersion.current().getVersion(),
            vcsBranch: Git.current().branchName,
            vcsCommits: [Git.current().commitId],
            testTime: System.currentTimeMillis())

        LinkedHashSet baselineVersions = toBaselineVersions(releases, targetVersions, adhocRun)

        baselineVersions.each { it ->
            def baselineVersion = results.baseline(it)
            baselineVersion.maxExecutionTimeRegression = maxExecutionTimeRegression
            baselineVersion.maxMemoryRegression = maxMemoryRegression
            runVersion(buildContext.distribution(baselineVersion.version), workingDir, baselineVersion.results)
        }

        runVersion(current, workingDir, results.current)

        results.assertEveryBuildSucceeds()

        // Don't store results when builds have failed
        reporter.report(results)

        results.assertCurrentVersionHasNotRegressed()

        return results
    }

    static LinkedHashSet<String> toBaselineVersions(ReleasedVersionDistributions releases, List<String> targetVersions, boolean adhocRun) {
        def mostRecentFinalRelease = releases.mostRecentFinalRelease.version.version
        def mostRecentSnapshot = releases.mostRecentSnapshot.version.version
        def currentBaseVersion = GradleVersion.current().getBaseVersion().version
        def baselineVersions = new LinkedHashSet()


        // TODO: Make it possible to set the baseline version from the command line
        if (adhocRun) {
            baselineVersions.add(mostRecentSnapshot)
        } else {
            for (String version : targetVersions) {
                if (version == 'last' || version == 'nightly' || version == currentBaseVersion) {
                    // These are all treated specially below
                    continue
                }
                baselineVersions.add(findRelease(releases, version).version.version)
            }
            if (!targetVersions.contains('nightly')) {
                // Include the most recent final release if we're not testing against a nightly
                baselineVersions.add(mostRecentFinalRelease)
            } else {
                baselineVersions.add(mostRecentSnapshot)
            }
        }
        baselineVersions
    }

    protected static GradleDistribution findRelease(ReleasedVersionDistributions releases, String requested) {
        GradleDistribution best = null
        for (GradleDistribution release : releases.all) {
            if (release.version.version == requested) {
                return release
            }
            if (!release.version.snapshot && release.version.baseVersion.version == requested && (best == null || best.version < release.version)) {
                best = release
            }
        }
        if (best != null) {
            return best
        }
        throw new RuntimeException("Cannot find Gradle release that matches version '" + requested + "'")
    }

    private void runVersion(GradleDistribution dist, File workingDir, MeasuredOperationList results) {
        def gradleOptsInUse = resolveGradleOpts()
        def builder = GradleBuildExperimentSpec.builder()
            .projectName(testProject)
            .displayName(dist.version.version)
            .warmUpCount(warmUpRuns)
            .invocationCount(runs)
            .listener(buildExperimentListener)
            .invocation {
                workingDirectory(workingDir)
                distribution(dist)
                tasksToRun(this.tasksToRun as String[])
                args(this.args as String[])
                gradleOpts(gradleOptsInUse as String[])
                useDaemon(this.useDaemon)
            }

        def spec = builder.build()

        experimentRunner.run(spec, results)
    }

    def resolveGradleOpts() {
        def gradleOptsInUse = [] + this.gradleOpts
        if (!JavaVersion.current().isJava8Compatible() && gradleOptsInUse.count { it.startsWith('-XX:MaxPermSize=') } == 0) {
            gradleOptsInUse << "-XX:MaxPermSize=${maxPermSizeMB}m".toString()
        }
        gradleOptsInUse
    }

}
