/*
 * Copyright 2019 the original author or authors.
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

package org.gradle.internal.execution.steps

import com.google.common.collect.ImmutableSortedMap
import org.gradle.internal.execution.AfterPreviousExecutionContext
import org.gradle.internal.execution.BeforeExecutionContext
import org.gradle.internal.execution.CachingResult
import org.gradle.internal.execution.Step
import org.gradle.internal.execution.UnitOfWork
import org.gradle.internal.execution.history.AfterPreviousExecutionState
import org.gradle.internal.fingerprint.CurrentFileCollectionFingerprint
import org.gradle.internal.fingerprint.FileCollectionFingerprint
import org.gradle.internal.fingerprint.impl.AbsolutePathFingerprintingStrategy
import org.gradle.internal.hash.ClassLoaderHierarchyHasher
import org.gradle.internal.hash.HashCode
import org.gradle.internal.snapshot.FileSystemSnapshot
import org.gradle.internal.snapshot.ValueSnapshot
import org.gradle.internal.snapshot.ValueSnapshotter
import org.gradle.internal.snapshot.impl.ImplementationSnapshot
import spock.lang.Specification

class CaptureStateBeforeExecutionStepTest extends Specification {

    def classloaderHierarchyHasher = Mock(ClassLoaderHierarchyHasher)
    def valueSnapshotter = Mock(ValueSnapshotter)
    def work = Mock(UnitOfWork)
    def afterPreviousExecutionContext = Stub(AfterPreviousExecutionContext) {
        getWork() >> this.work
    }
    Step<BeforeExecutionContext, CachingResult> delegate = Mock()
    def implementationSnapshot = ImplementationSnapshot.of("MyWorkClass", HashCode.fromInt(1234))

    def step = new CaptureStateBeforeExecutionStep(classloaderHierarchyHasher, valueSnapshotter, delegate)

    def "no state is captured when task history is not maintained"() {
        when:
        step.execute(afterPreviousExecutionContext)
        then:
        1 * work.isTaskHistoryMaintained() >> false
        1 * delegate.execute(_) >> { BeforeExecutionContext beforeExecution ->
            assert beforeExecution.beforeExecutionState.empty
        }
        0 * _
    }

    def "implementations are snapshotted"() {
        def additionalImplementations = [
            ImplementationSnapshot.of("FirstAction", HashCode.fromInt(2345)),
            ImplementationSnapshot.of("SecondAction", HashCode.fromInt(3456))
        ]

        when:
        step.execute(afterPreviousExecutionContext)
        then:
        1 * work.visitImplementations(_) >> { UnitOfWork.ImplementationVisitor visitor ->
            visitor.visitImplementation(implementationSnapshot)
            additionalImplementations.each {
                visitor.visitAdditionalImplementation(it)
            }
        }
        interaction { fingerprintInputs() }
        1 * delegate.execute(_) >> { BeforeExecutionContext beforeExecution ->
            def state = beforeExecution.beforeExecutionState.get()
            assert state.implementation == implementationSnapshot
            assert state.additionalImplementations == additionalImplementations
        }
        0 * _
    }

    def "input properties are snapshotted"() {
        def inputPropertyValue = 'myValue'
        def valueSnapshot = Mock(ValueSnapshot)

        when:
        step.execute(afterPreviousExecutionContext)
        then:
        1 * work.visitInputProperties(_) >> { UnitOfWork.InputPropertyVisitor visitor ->
            visitor.visitInputProperty("inputString", inputPropertyValue)
        }
        1 * valueSnapshotter.snapshot(inputPropertyValue) >> valueSnapshot
        interaction { fingerprintInputs() }
        1 * delegate.execute(_) >> { BeforeExecutionContext beforeExecution ->
            def state = beforeExecution.beforeExecutionState.get()
            assert state.inputProperties == ImmutableSortedMap.<String, ValueSnapshot>of('inputString', valueSnapshot)
        }
        0 * _
    }

    def "uses previous input property snapshots"() {
        def inputPropertyValue = 'myValue'
        def valueSnapshot = Mock(ValueSnapshot)
        def afterPreviousExecutionState = Mock(AfterPreviousExecutionState)

        when:
        step.execute(afterPreviousExecutionContext)
        then:
        afterPreviousExecutionContext.afterPreviousExecutionState >> Optional.of(afterPreviousExecutionState)
        1 * afterPreviousExecutionState.inputProperties >> ImmutableSortedMap.<String, ValueSnapshot>of("inputString", valueSnapshot)
        1 * afterPreviousExecutionState.outputFileProperties >> ImmutableSortedMap.<String, FileCollectionFingerprint>of()
        1 * work.visitInputProperties(_) >> { UnitOfWork.InputPropertyVisitor visitor ->
            visitor.visitInputProperty("inputString", inputPropertyValue)
        }
        1 * valueSnapshotter.snapshot(inputPropertyValue, valueSnapshot) >> valueSnapshot
        interaction { fingerprintInputs() }
        1 * delegate.execute(_) >> { BeforeExecutionContext beforeExecution ->
            def state = beforeExecution.beforeExecutionState.get()
            assert state.inputProperties == ImmutableSortedMap.<String, ValueSnapshot>of('inputString', valueSnapshot)
        }
        0 * _
    }

    def "input file properties are fingerprinted"() {
        def fingerprint = Mock(CurrentFileCollectionFingerprint)

        when:
        step.execute(afterPreviousExecutionContext)
        then:
        1 * work.visitInputFileProperties(_) >> { UnitOfWork.InputFilePropertyVisitor visitor ->
            visitor.visitInputFileProperty("inputFile", "ignored", false, { -> fingerprint })
        }
        interaction { fingerprintInputs() }
        1 * delegate.execute(_) >> { BeforeExecutionContext beforeExecution ->
            def state = beforeExecution.beforeExecutionState.get()
            assert state.inputFileProperties == ImmutableSortedMap.<String, CurrentFileCollectionFingerprint>of('inputFile', fingerprint)
        }
        0 * _
    }

    def "output file properties are fingerprinted"() {
        def outputFileSnapshot = Mock(FileSystemSnapshot)

        when:
        step.execute(afterPreviousExecutionContext)
        then:
        1 * work.outputFileSnapshotsBeforeExecution >> ImmutableSortedMap.<String, FileSystemSnapshot>of("outputDir", outputFileSnapshot)
        interaction { fingerprintInputs() }
        1 * delegate.execute(_) >> { BeforeExecutionContext beforeExecution ->
            def state = beforeExecution.beforeExecutionState.get()
            assert state.outputFileProperties == ImmutableSortedMap.<String, CurrentFileCollectionFingerprint>of('outputDir', AbsolutePathFingerprintingStrategy.IGNORE_MISSING.emptyFingerprint)
        }
        0 * _
    }

    def "uses before output snapshot when there are no overlapping outputs"() {
        def afterPreviousExecutionState = Mock(AfterPreviousExecutionState)
        def afterPreviousOutputFingerprint = Mock(FileCollectionFingerprint)
        def outputFileSnapshot = Mock(FileSystemSnapshot)

        when:
        step.execute(afterPreviousExecutionContext)
        then:
        afterPreviousExecutionContext.afterPreviousExecutionState >> Optional.of(afterPreviousExecutionState)
        1 * afterPreviousExecutionState.inputProperties >> ImmutableSortedMap.<String, ValueSnapshot>of()
        1 * afterPreviousExecutionState.outputFileProperties >> ImmutableSortedMap.<String, FileCollectionFingerprint>of("outputDir", afterPreviousOutputFingerprint)

        1 * work.outputFileSnapshotsBeforeExecution >> ImmutableSortedMap.<String, FileSystemSnapshot>of("outputDir", outputFileSnapshot)
        1 * outputFileSnapshot.accept(_)

        interaction { fingerprintInputs() }
        1 * delegate.execute(_) >> { BeforeExecutionContext beforeExecution ->
            def state = beforeExecution.beforeExecutionState.get()
            assert state.outputFileProperties == ImmutableSortedMap.<String, CurrentFileCollectionFingerprint>of('outputDir', AbsolutePathFingerprintingStrategy.IGNORE_MISSING.emptyFingerprint)
        }
        0 * _
    }

    def "filters before output snapshot when there are overlapping outputs"() {
        def afterPreviousExecutionState = Mock(AfterPreviousExecutionState)
        def afterPreviousOutputFingerprint = Mock(FileCollectionFingerprint)
        def outputFileSnapshot = Mock(FileSystemSnapshot)

        when:
        step.execute(afterPreviousExecutionContext)
        then:
        afterPreviousExecutionContext.afterPreviousExecutionState >> Optional.of(afterPreviousExecutionState)
        1 * afterPreviousExecutionState.inputProperties >> ImmutableSortedMap.<String, ValueSnapshot>of()
        1 * afterPreviousExecutionState.outputFileProperties >> ImmutableSortedMap.<String, FileCollectionFingerprint>of("outputDir", afterPreviousOutputFingerprint)
        1 * work.hasOverlappingOutputs() >> true

        1 * work.outputFileSnapshotsBeforeExecution >> ImmutableSortedMap.<String, FileSystemSnapshot>of("outputDir", outputFileSnapshot)
        1 * outputFileSnapshot.accept(_)
        1 * afterPreviousOutputFingerprint.fingerprints >> [:]

        interaction { fingerprintInputs() }
        1 * delegate.execute(_) >> { BeforeExecutionContext beforeExecution ->
            def state = beforeExecution.beforeExecutionState.get()
            assert state.outputFileProperties == ImmutableSortedMap.<String, CurrentFileCollectionFingerprint>of('outputDir', AbsolutePathFingerprintingStrategy.IGNORE_MISSING.emptyFingerprint)
        }
        0 * _
    }

    void fingerprintInputs() {
        _ * afterPreviousExecutionContext.afterPreviousExecutionState >> Optional.empty()
        _ * work.visitImplementations(_) >> { UnitOfWork.ImplementationVisitor visitor ->
            visitor.visitImplementation(implementationSnapshot)
        }
        _ * work.visitInputProperties(_)
        _ * work.visitInputFileProperties(_)
        _ * work.hasOverlappingOutputs() >> false
        _ * work.getOutputFileSnapshotsBeforeExecution() >> ImmutableSortedMap.of()
        1 * work.isTaskHistoryMaintained() >> true
    }

}
