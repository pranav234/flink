/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.minicluster;

import org.apache.flink.api.common.JobSubmissionResult;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.JobManagerOptions;
import org.apache.flink.configuration.ResourceManagerOptions;
import org.apache.flink.core.testutils.FlinkAssertions;
import org.apache.flink.runtime.client.JobExecutionException;
import org.apache.flink.runtime.io.network.partition.ResultPartitionType;
import org.apache.flink.runtime.jobgraph.DistributionPattern;
import org.apache.flink.runtime.jobgraph.JobGraph;
import org.apache.flink.runtime.jobgraph.JobGraphTestUtils;
import org.apache.flink.runtime.jobgraph.JobVertex;
import org.apache.flink.runtime.jobmanager.Tasks.AgnosticBinaryReceiver;
import org.apache.flink.runtime.jobmanager.Tasks.AgnosticReceiver;
import org.apache.flink.runtime.jobmanager.Tasks.AgnosticTertiaryReceiver;
import org.apache.flink.runtime.jobmanager.Tasks.ExceptionReceiver;
import org.apache.flink.runtime.jobmanager.Tasks.ExceptionSender;
import org.apache.flink.runtime.jobmanager.Tasks.Forwarder;
import org.apache.flink.runtime.jobmanager.Tasks.InstantiationErrorSender;
import org.apache.flink.runtime.jobmanager.scheduler.NoResourceAvailableException;
import org.apache.flink.runtime.jobmanager.scheduler.SlotSharingGroup;
import org.apache.flink.runtime.jobmaster.JobResult;
import org.apache.flink.runtime.jobmaster.TestingAbstractInvokables.Receiver;
import org.apache.flink.runtime.jobmaster.TestingAbstractInvokables.Sender;
import org.apache.flink.runtime.testtasks.BlockingNoOpInvokable;
import org.apache.flink.runtime.testtasks.NoOpInvokable;
import org.apache.flink.runtime.testtasks.WaitingNoOpInvokable;
import org.apache.flink.streaming.util.RestartStrategyUtils;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.apache.flink.runtime.util.JobVertexConnectionUtils.connectNewDataSetAsInput;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Integration test cases for the {@link MiniCluster}. */
class MiniClusterITCase {

    @Test
    void runJobWithSingleRpcService() throws Exception {
        final int numOfTMs = 3;
        final int slotsPerTM = 7;

        final MiniClusterConfiguration cfg =
                new MiniClusterConfiguration.Builder()
                        .withRandomPorts()
                        .setNumTaskManagers(numOfTMs)
                        .setNumSlotsPerTaskManager(slotsPerTM)
                        .setRpcServiceSharing(RpcServiceSharing.SHARED)
                        .build();

        try (final MiniCluster miniCluster = new MiniCluster(cfg)) {
            miniCluster.start();

            miniCluster.executeJobBlocking(getSimpleJob(numOfTMs * slotsPerTM));
        }
    }

    @Test
    void runJobWithMultipleRpcServices() throws Exception {
        final int numOfTMs = 3;
        final int slotsPerTM = 7;

        final MiniClusterConfiguration cfg =
                new MiniClusterConfiguration.Builder()
                        .withRandomPorts()
                        .setNumTaskManagers(numOfTMs)
                        .setNumSlotsPerTaskManager(slotsPerTM)
                        .setRpcServiceSharing(RpcServiceSharing.DEDICATED)
                        .build();

        try (final MiniCluster miniCluster = new MiniCluster(cfg)) {
            miniCluster.start();

            miniCluster.executeJobBlocking(getSimpleJob(numOfTMs * slotsPerTM));
        }
    }

    @Test
    void testHandlingNotEnoughSlotsThroughTimeout() throws Exception {
        final Configuration config = new Configuration();

        // the slot timeout needs to be high enough to avoid causing TimeoutException
        final Duration slotRequestTimeout = Duration.ofMillis(100);

        // this triggers the failure for the default scheduler
        config.set(JobManagerOptions.SLOT_REQUEST_TIMEOUT, slotRequestTimeout);
        // this triggers the failure for the adaptive scheduler
        config.set(
                JobManagerOptions.SCHEDULER_SUBMISSION_RESOURCE_WAIT_TIMEOUT, slotRequestTimeout);

        // we have to disable sending the slot-unavailable request to allow for the timeout to kick
        // in
        config.set(
                ResourceManagerOptions.REQUIREMENTS_CHECK_DELAY, Duration.ofNanos(Long.MAX_VALUE));

        tryRunningJobWithoutEnoughSlots(config);
    }

    @Test
    // The AdaptiveScheduler is supposed to work with the resources that are available.
    // That is why there is no resource allocation abort request supported.
    @Tag("org.apache.flink.testutils.junit.FailsWithAdaptiveScheduler")
    void testHandlingNotEnoughSlotsThroughEarlyAbortRequest() throws Exception {
        final Configuration config = new Configuration();

        // the slot timeout needs to be high enough to avoid causing TimeoutException
        final Duration slotRequestTimeout = Duration.ofNanos(Long.MAX_VALUE);

        // this triggers the failure for the default scheduler
        config.set(JobManagerOptions.SLOT_REQUEST_TIMEOUT, slotRequestTimeout);
        // this triggers the failure for the adaptive scheduler
        config.set(
                JobManagerOptions.SCHEDULER_SUBMISSION_RESOURCE_WAIT_TIMEOUT, slotRequestTimeout);

        // overwrite the default check delay to speed up the test execution
        config.set(ResourceManagerOptions.REQUIREMENTS_CHECK_DELAY, Duration.ofMillis(20));

        // cluster startup relies on SLOT_REQUEST_TIMEOUT as a fallback if the following parameter
        // is not set which causes the test to take longer
        config.set(
                ResourceManagerOptions.STANDALONE_CLUSTER_STARTUP_PERIOD_TIME,
                Duration.ofMillis(1L));

        tryRunningJobWithoutEnoughSlots(config);
    }

    private static void tryRunningJobWithoutEnoughSlots(Configuration configuration)
            throws Exception {
        final JobVertex vertex1 = new JobVertex("Test Vertex1");
        vertex1.setParallelism(1);
        vertex1.setMaxParallelism(1);
        vertex1.setInvokableClass(BlockingNoOpInvokable.class);

        final JobVertex vertex2 = new JobVertex("Test Vertex2");
        vertex2.setParallelism(1);
        vertex2.setMaxParallelism(1);
        vertex2.setInvokableClass(BlockingNoOpInvokable.class);

        connectNewDataSetAsInput(
                vertex2, vertex1, DistributionPattern.POINTWISE, ResultPartitionType.PIPELINED);

        final JobGraph jobGraph = JobGraphTestUtils.streamingJobGraph(vertex1, vertex2);

        final MiniClusterConfiguration cfg =
                new MiniClusterConfiguration.Builder()
                        .withRandomPorts()
                        .setNumTaskManagers(1)
                        .setNumSlotsPerTaskManager(1)
                        .setConfiguration(configuration)
                        .build();

        try (final MiniCluster miniCluster = new MiniCluster(cfg)) {
            miniCluster.start();

            assertThatThrownBy(() -> miniCluster.executeJobBlocking(jobGraph))
                    .isInstanceOf(JobExecutionException.class)
                    .hasMessageContaining("Job execution failed")
                    .extracting(Throwable::getCause)
                    .extracting(FlinkAssertions::chainOfCauses, FlinkAssertions.STREAM_THROWABLE)
                    .anySatisfy(
                            cause ->
                                    assertThat(cause)
                                            .isInstanceOf(NoResourceAvailableException.class));
        }
    }

    @Test
    void testForwardJob() throws Exception {
        final int parallelism = 31;

        final MiniClusterConfiguration cfg =
                new MiniClusterConfiguration.Builder()
                        .withRandomPorts()
                        .setNumTaskManagers(1)
                        .setNumSlotsPerTaskManager(2 * parallelism)
                        .build();

        try (final MiniCluster miniCluster = new MiniCluster(cfg)) {
            miniCluster.start();

            final JobVertex sender = new JobVertex("Sender");
            sender.setInvokableClass(Sender.class);
            sender.setParallelism(parallelism);

            final JobVertex receiver = new JobVertex("Receiver");
            receiver.setInvokableClass(Receiver.class);
            receiver.setParallelism(parallelism);

            connectNewDataSetAsInput(
                    receiver, sender, DistributionPattern.POINTWISE, ResultPartitionType.PIPELINED);

            final JobGraph jobGraph = JobGraphTestUtils.streamingJobGraph(sender, receiver);

            miniCluster.executeJobBlocking(jobGraph);
        }
    }

    @Test
    void testBipartiteJob() throws Exception {
        final int parallelism = 31;

        final MiniClusterConfiguration cfg =
                new MiniClusterConfiguration.Builder()
                        .withRandomPorts()
                        .setNumTaskManagers(1)
                        .setNumSlotsPerTaskManager(2 * parallelism)
                        .build();

        try (final MiniCluster miniCluster = new MiniCluster(cfg)) {
            miniCluster.start();

            final JobVertex sender = new JobVertex("Sender");
            sender.setInvokableClass(Sender.class);
            sender.setParallelism(parallelism);

            final JobVertex receiver = new JobVertex("Receiver");
            receiver.setInvokableClass(AgnosticReceiver.class);
            receiver.setParallelism(parallelism);

            connectNewDataSetAsInput(
                    receiver, sender, DistributionPattern.POINTWISE, ResultPartitionType.PIPELINED);

            final JobGraph jobGraph = JobGraphTestUtils.streamingJobGraph(sender, receiver);

            miniCluster.executeJobBlocking(jobGraph);
        }
    }

    @Test
    void testTwoInputJobFailingEdgeMismatch() throws Exception {
        final int parallelism = 1;

        final MiniClusterConfiguration cfg =
                new MiniClusterConfiguration.Builder()
                        .withRandomPorts()
                        .setNumTaskManagers(1)
                        .setNumSlotsPerTaskManager(6 * parallelism)
                        .build();

        try (final MiniCluster miniCluster = new MiniCluster(cfg)) {
            miniCluster.start();

            final JobVertex sender1 = new JobVertex("Sender1");
            sender1.setInvokableClass(Sender.class);
            sender1.setParallelism(parallelism);

            final JobVertex sender2 = new JobVertex("Sender2");
            sender2.setInvokableClass(Sender.class);
            sender2.setParallelism(2 * parallelism);

            final JobVertex receiver = new JobVertex("Receiver");
            receiver.setInvokableClass(AgnosticTertiaryReceiver.class);
            receiver.setParallelism(3 * parallelism);

            connectNewDataSetAsInput(
                    receiver,
                    sender1,
                    DistributionPattern.POINTWISE,
                    ResultPartitionType.PIPELINED);
            connectNewDataSetAsInput(
                    receiver,
                    sender2,
                    DistributionPattern.ALL_TO_ALL,
                    ResultPartitionType.PIPELINED);

            final JobGraph jobGraph =
                    JobGraphTestUtils.streamingJobGraph(sender1, receiver, sender2);

            assertThatThrownBy(() -> miniCluster.executeJobBlocking(jobGraph))
                    .isInstanceOf(JobExecutionException.class)
                    .hasRootCauseInstanceOf(ArrayIndexOutOfBoundsException.class)
                    .rootCause()
                    .hasMessageContaining("2");
        }
    }

    @Test
    void testTwoInputJob() throws Exception {
        final int parallelism = 11;

        final MiniClusterConfiguration cfg =
                new MiniClusterConfiguration.Builder()
                        .withRandomPorts()
                        .setNumTaskManagers(1)
                        .setNumSlotsPerTaskManager(6 * parallelism)
                        .build();

        try (final MiniCluster miniCluster = new MiniCluster(cfg)) {
            miniCluster.start();

            final JobVertex sender1 = new JobVertex("Sender1");
            sender1.setInvokableClass(Sender.class);
            sender1.setParallelism(parallelism);

            final JobVertex sender2 = new JobVertex("Sender2");
            sender2.setInvokableClass(Sender.class);
            sender2.setParallelism(2 * parallelism);

            final JobVertex receiver = new JobVertex("Receiver");
            receiver.setInvokableClass(AgnosticBinaryReceiver.class);
            receiver.setParallelism(3 * parallelism);

            connectNewDataSetAsInput(
                    receiver,
                    sender1,
                    DistributionPattern.POINTWISE,
                    ResultPartitionType.PIPELINED);
            connectNewDataSetAsInput(
                    receiver,
                    sender2,
                    DistributionPattern.ALL_TO_ALL,
                    ResultPartitionType.PIPELINED);

            final JobGraph jobGraph =
                    JobGraphTestUtils.streamingJobGraph(sender1, receiver, sender2);

            miniCluster.executeJobBlocking(jobGraph);
        }
    }

    @Test
    void testSchedulingAllAtOnce() throws Exception {
        final int parallelism = 11;

        final MiniClusterConfiguration cfg =
                new MiniClusterConfiguration.Builder()
                        .withRandomPorts()
                        .setNumTaskManagers(1)
                        .setNumSlotsPerTaskManager(parallelism)
                        .build();

        try (final MiniCluster miniCluster = new MiniCluster(cfg)) {
            miniCluster.start();

            final JobVertex sender = new JobVertex("Sender");
            sender.setInvokableClass(Sender.class);
            sender.setParallelism(parallelism);

            final JobVertex forwarder = new JobVertex("Forwarder");
            forwarder.setInvokableClass(Forwarder.class);
            forwarder.setParallelism(parallelism);

            final JobVertex receiver = new JobVertex("Receiver");
            receiver.setInvokableClass(AgnosticReceiver.class);
            receiver.setParallelism(parallelism);

            final SlotSharingGroup sharingGroup = new SlotSharingGroup();
            sender.setSlotSharingGroup(sharingGroup);
            forwarder.setSlotSharingGroup(sharingGroup);
            receiver.setSlotSharingGroup(sharingGroup);

            connectNewDataSetAsInput(
                    forwarder,
                    sender,
                    DistributionPattern.ALL_TO_ALL,
                    ResultPartitionType.PIPELINED);
            connectNewDataSetAsInput(
                    receiver,
                    forwarder,
                    DistributionPattern.ALL_TO_ALL,
                    ResultPartitionType.PIPELINED);

            final JobGraph jobGraph =
                    JobGraphTestUtils.streamingJobGraph(sender, forwarder, receiver);

            miniCluster.executeJobBlocking(jobGraph);
        }
    }

    @Test
    void testJobWithAFailingSenderVertex() throws Exception {
        final int parallelism = 11;

        final MiniClusterConfiguration cfg =
                new MiniClusterConfiguration.Builder()
                        .withRandomPorts()
                        .setNumTaskManagers(1)
                        .setNumSlotsPerTaskManager(2 * parallelism)
                        .build();

        try (final MiniCluster miniCluster = new MiniCluster(cfg)) {
            miniCluster.start();

            final JobVertex sender = new JobVertex("Sender");
            sender.setInvokableClass(ExceptionSender.class);
            sender.setParallelism(parallelism);

            final JobVertex receiver = new JobVertex("Receiver");
            receiver.setInvokableClass(Receiver.class);
            receiver.setParallelism(parallelism);

            connectNewDataSetAsInput(
                    receiver, sender, DistributionPattern.POINTWISE, ResultPartitionType.PIPELINED);

            final JobGraph jobGraph = JobGraphTestUtils.streamingJobGraph(sender, receiver);

            assertThatThrownBy(() -> miniCluster.executeJobBlocking(jobGraph))
                    .isInstanceOf(JobExecutionException.class)
                    .hasRootCauseInstanceOf(Exception.class)
                    .rootCause()
                    .hasMessageContaining("Test exception");
        }
    }

    @Test
    void testJobWithAnOccasionallyFailingSenderVertex() throws Exception {
        final int parallelism = 11;

        final MiniClusterConfiguration cfg =
                new MiniClusterConfiguration.Builder()
                        .withRandomPorts()
                        .setNumTaskManagers(1)
                        .setNumSlotsPerTaskManager(parallelism)
                        .build();

        try (final MiniCluster miniCluster = new MiniCluster(cfg)) {
            miniCluster.start();

            // putting sender and receiver vertex in the same slot sharing group is required
            // to ensure all senders can be deployed. Otherwise this case can fail if the
            // expected failing sender is not deployed.
            final SlotSharingGroup group = new SlotSharingGroup();

            final JobVertex sender = new JobVertex("Sender");
            sender.setInvokableClass(SometimesExceptionSender.class);
            sender.setParallelism(parallelism);
            sender.setSlotSharingGroup(group);

            // set failing senders
            SometimesExceptionSender.configFailingSenders(parallelism);

            final JobVertex receiver = new JobVertex("Receiver");
            receiver.setInvokableClass(Receiver.class);
            receiver.setParallelism(parallelism);
            receiver.setSlotSharingGroup(group);

            connectNewDataSetAsInput(
                    receiver, sender, DistributionPattern.POINTWISE, ResultPartitionType.PIPELINED);

            final JobGraph jobGraph = JobGraphTestUtils.streamingJobGraph(sender, receiver);

            assertThatThrownBy(() -> miniCluster.executeJobBlocking(jobGraph))
                    .isInstanceOf(JobExecutionException.class)
                    .hasRootCauseInstanceOf(Exception.class)
                    .rootCause()
                    .hasMessageContaining("Test exception");
        }
    }

    @Test
    void testJobWithAFailingReceiverVertex() throws Exception {
        final int parallelism = 11;

        final MiniClusterConfiguration cfg =
                new MiniClusterConfiguration.Builder()
                        .withRandomPorts()
                        .setNumTaskManagers(1)
                        .setNumSlotsPerTaskManager(2 * parallelism)
                        .build();

        try (final MiniCluster miniCluster = new MiniCluster(cfg)) {
            miniCluster.start();

            final JobVertex sender = new JobVertex("Sender");
            sender.setInvokableClass(Sender.class);
            sender.setParallelism(parallelism);

            final JobVertex receiver = new JobVertex("Receiver");
            receiver.setInvokableClass(ExceptionReceiver.class);
            receiver.setParallelism(parallelism);

            connectNewDataSetAsInput(
                    receiver, sender, DistributionPattern.POINTWISE, ResultPartitionType.PIPELINED);

            final JobGraph jobGraph = JobGraphTestUtils.streamingJobGraph(sender, receiver);

            assertThatThrownBy(() -> miniCluster.executeJobBlocking(jobGraph))
                    .isInstanceOf(JobExecutionException.class)
                    .hasRootCauseInstanceOf(Exception.class)
                    .rootCause()
                    .hasMessageContaining("Test exception");
        }
    }

    @Test
    void testJobWithAllVerticesFailingDuringInstantiation() throws Exception {
        final int parallelism = 11;

        final MiniClusterConfiguration cfg =
                new MiniClusterConfiguration.Builder()
                        .withRandomPorts()
                        .setNumTaskManagers(1)
                        .setNumSlotsPerTaskManager(2 * parallelism)
                        .build();

        try (final MiniCluster miniCluster = new MiniCluster(cfg)) {
            miniCluster.start();

            final JobVertex sender = new JobVertex("Sender");
            sender.setInvokableClass(InstantiationErrorSender.class);
            sender.setParallelism(parallelism);

            final JobVertex receiver = new JobVertex("Receiver");
            receiver.setInvokableClass(Receiver.class);
            receiver.setParallelism(parallelism);

            connectNewDataSetAsInput(
                    receiver, sender, DistributionPattern.POINTWISE, ResultPartitionType.PIPELINED);

            final JobGraph jobGraph = JobGraphTestUtils.streamingJobGraph(sender, receiver);

            assertThatThrownBy(() -> miniCluster.executeJobBlocking(jobGraph))
                    .isInstanceOf(JobExecutionException.class)
                    .hasRootCauseInstanceOf(Exception.class)
                    .rootCause()
                    .hasMessageContaining("Test exception in constructor");
        }
    }

    @Test
    void testJobWithSomeVerticesFailingDuringInstantiation() throws Exception {
        final int parallelism = 11;

        final MiniClusterConfiguration cfg =
                new MiniClusterConfiguration.Builder()
                        .withRandomPorts()
                        .setNumTaskManagers(1)
                        .setNumSlotsPerTaskManager(parallelism)
                        .build();

        try (final MiniCluster miniCluster = new MiniCluster(cfg)) {
            miniCluster.start();

            // putting sender and receiver vertex in the same slot sharing group is required
            // to ensure all senders can be deployed. Otherwise this case can fail if the
            // expected failing sender is not deployed.
            final SlotSharingGroup group = new SlotSharingGroup();

            final JobVertex sender = new JobVertex("Sender");
            sender.setInvokableClass(SometimesInstantiationErrorSender.class);
            sender.setParallelism(parallelism);
            sender.setSlotSharingGroup(group);

            // set failing senders
            SometimesInstantiationErrorSender.configFailingSenders(parallelism);

            final JobVertex receiver = new JobVertex("Receiver");
            receiver.setInvokableClass(Receiver.class);
            receiver.setParallelism(parallelism);
            receiver.setSlotSharingGroup(group);

            connectNewDataSetAsInput(
                    receiver, sender, DistributionPattern.POINTWISE, ResultPartitionType.PIPELINED);

            final JobGraph jobGraph = JobGraphTestUtils.streamingJobGraph(sender, receiver);

            assertThatThrownBy(() -> miniCluster.executeJobBlocking(jobGraph))
                    .isInstanceOf(JobExecutionException.class)
                    .hasCauseInstanceOf(Exception.class)
                    .rootCause()
                    .hasMessageContaining("Test exception in constructor");
        }
    }

    @Test
    void testCallFinalizeOnMasterBeforeJobCompletes() throws Exception {
        final int parallelism = 11;

        final MiniClusterConfiguration cfg =
                new MiniClusterConfiguration.Builder()
                        .withRandomPorts()
                        .setNumTaskManagers(1)
                        .setNumSlotsPerTaskManager(2 * parallelism)
                        .build();

        try (final MiniCluster miniCluster = new MiniCluster(cfg)) {
            miniCluster.start();

            final JobVertex source = new JobVertex("Source");
            source.setInvokableClass(WaitingNoOpInvokable.class);
            source.setParallelism(parallelism);

            WaitOnFinalizeJobVertex.resetFinalizedOnMaster();

            final WaitOnFinalizeJobVertex sink = new WaitOnFinalizeJobVertex("Sink", 20L);
            sink.setInvokableClass(NoOpInvokable.class);
            sink.setParallelism(parallelism);

            connectNewDataSetAsInput(
                    sink, source, DistributionPattern.POINTWISE, ResultPartitionType.PIPELINED);

            final JobGraph jobGraph = JobGraphTestUtils.streamingJobGraph(source, sink);

            final CompletableFuture<JobSubmissionResult> submissionFuture =
                    miniCluster.submitJob(jobGraph);

            final CompletableFuture<JobResult> jobResultFuture =
                    submissionFuture.thenCompose(
                            (JobSubmissionResult ignored) ->
                                    miniCluster.requestJobResult(jobGraph.getJobID()));

            jobResultFuture.get().toJobExecutionResult(getClass().getClassLoader());

            assertThat(WaitOnFinalizeJobVertex.finalizedOnMaster).isTrue();
        }
    }

    @Test
    void testOutOfMemoryErrorMessageEnrichmentInJobVertexFinalization() throws Exception {
        final int parallelism = 1;

        final MiniClusterConfiguration cfg =
                new MiniClusterConfiguration.Builder()
                        .withRandomPorts()
                        .setNumTaskManagers(1)
                        .setNumSlotsPerTaskManager(parallelism)
                        .build();

        try (final MiniCluster miniCluster = new MiniCluster(cfg)) {
            miniCluster.start();

            final JobVertex failingJobVertex = new OutOfMemoryInFinalizationJobVertex();
            failingJobVertex.setInvokableClass(NoOpInvokable.class);
            failingJobVertex.setParallelism(parallelism);

            final JobGraph jobGraph = JobGraphTestUtils.streamingJobGraph(failingJobVertex);

            final CompletableFuture<JobSubmissionResult> submissionFuture =
                    miniCluster.submitJob(jobGraph);

            final CompletableFuture<JobResult> jobResultFuture =
                    submissionFuture.thenCompose(
                            (JobSubmissionResult ignored) ->
                                    miniCluster.requestJobResult(jobGraph.getJobID()));

            assertThatThrownBy(
                            () ->
                                    jobResultFuture
                                            .get()
                                            .toJobExecutionResult(getClass().getClassLoader()))
                    .isInstanceOf(JobExecutionException.class)
                    .hasRootCauseInstanceOf(OutOfMemoryError.class)
                    .rootCause()
                    .hasMessageContaining(
                            "Java heap space. A heap space-related out-of-memory error has occurred.");
        }
    }

    @Test
    void testOutOfMemoryErrorMessageEnrichmentInJobVertexInitialization() throws Exception {
        final int parallelism = 1;

        final MiniClusterConfiguration cfg =
                new MiniClusterConfiguration.Builder()
                        .withRandomPorts()
                        .setNumTaskManagers(1)
                        .setNumSlotsPerTaskManager(parallelism)
                        .build();

        try (final MiniCluster miniCluster = new MiniCluster(cfg)) {
            miniCluster.start();

            final JobVertex failingJobVertex = new OutOfMemoryInInitializationVertex();
            failingJobVertex.setInvokableClass(NoOpInvokable.class);
            failingJobVertex.setParallelism(parallelism);

            final JobGraph jobGraph = JobGraphTestUtils.streamingJobGraph(failingJobVertex);

            final CompletableFuture<JobSubmissionResult> submissionFuture =
                    miniCluster.submitJob(jobGraph);

            final CompletableFuture<JobResult> jobResultFuture =
                    submissionFuture.thenCompose(
                            (JobSubmissionResult ignored) ->
                                    miniCluster.requestJobResult(jobGraph.getJobID()));

            assertThatThrownBy(
                            () ->
                                    jobResultFuture
                                            .get()
                                            .toJobExecutionResult(getClass().getClassLoader()))
                    .isInstanceOf(JobExecutionException.class)
                    .hasRootCauseInstanceOf(OutOfMemoryError.class)
                    .rootCause()
                    .hasMessageContaining("Java heap space");
        }
    }

    // ------------------------------------------------------------------------
    //  Utilities
    // ------------------------------------------------------------------------

    private static JobGraph getSimpleJob(int parallelism) throws IOException {
        final JobVertex task = new JobVertex("Test task");
        task.setParallelism(parallelism);
        task.setMaxParallelism(parallelism);
        task.setInvokableClass(NoOpInvokable.class);

        final JobGraph jg = JobGraphTestUtils.streamingJobGraph(task);

        RestartStrategyUtils.configureFixedDelayRestartStrategy(jg, Integer.MAX_VALUE, 1000);

        return jg;
    }

    private static class WaitOnFinalizeJobVertex extends JobVertex {

        private static final long serialVersionUID = -1179547322468530299L;

        private static final AtomicBoolean finalizedOnMaster = new AtomicBoolean(false);

        private final long waitingTime;

        WaitOnFinalizeJobVertex(String name, long waitingTime) {
            super(name);

            this.waitingTime = waitingTime;
        }

        @Override
        public void finalizeOnMaster(FinalizeOnMasterContext context) throws Exception {
            Thread.sleep(waitingTime);
            finalizedOnMaster.set(true);
        }

        static void resetFinalizedOnMaster() {
            finalizedOnMaster.set(false);
        }
    }

    private static class OutOfMemoryInFinalizationJobVertex extends JobVertex {

        private OutOfMemoryInFinalizationJobVertex() {
            super("FailingInFinalization");
        }

        @Override
        public void finalizeOnMaster(FinalizeOnMasterContext context) {
            throw new OutOfMemoryError("Java heap space");
        }
    }

    private static class OutOfMemoryInInitializationVertex extends JobVertex {

        OutOfMemoryInInitializationVertex() {
            super("FailingInInitialization");
        }

        @Override
        public void initializeOnMaster(InitializeOnMasterContext context) {
            throw new OutOfMemoryError("Java heap space");
        }
    }
}
