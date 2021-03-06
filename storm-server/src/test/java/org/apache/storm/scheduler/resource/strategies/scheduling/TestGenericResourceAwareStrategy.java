/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.storm.scheduler.resource.strategies.scheduling;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.storm.Config;
import org.apache.storm.generated.StormTopology;
import org.apache.storm.generated.WorkerResources;
import org.apache.storm.scheduler.Cluster;
import org.apache.storm.scheduler.ExecutorDetails;
import org.apache.storm.scheduler.INimbus;
import org.apache.storm.scheduler.IScheduler;
import org.apache.storm.scheduler.SchedulerAssignment;
import org.apache.storm.scheduler.SupervisorDetails;
import org.apache.storm.scheduler.SupervisorResources;
import org.apache.storm.scheduler.Topologies;
import org.apache.storm.scheduler.TopologyDetails;
import org.apache.storm.scheduler.WorkerSlot;
import org.apache.storm.scheduler.resource.ResourceAwareScheduler;
import org.apache.storm.topology.SharedOffHeapWithinNode;
import org.apache.storm.topology.SharedOffHeapWithinWorker;
import org.apache.storm.topology.SharedOnHeap;
import org.apache.storm.topology.TopologyBuilder;
import org.junit.After;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.storm.scheduler.resource.TestUtilsForResourceAwareScheduler.*;
import static org.junit.Assert.*;

import org.apache.storm.metric.StormMetricsRegistry;
import org.apache.storm.scheduler.resource.normalization.ResourceMetrics;

public class TestGenericResourceAwareStrategy {
    private static final Logger LOG = LoggerFactory.getLogger(TestGenericResourceAwareStrategy.class);

    private static int currentTime = 1450418597;
    private static IScheduler scheduler = null;

    @After
    public void cleanup() {
        if (scheduler != null) {
            scheduler.cleanup();
            scheduler = null;
        }
    }

    /**
     * test if the scheduling logic for the GenericResourceAwareStrategy is correct.
     */
    @Test
    public void testGenericResourceAwareStrategySharedMemory() {
        int spoutParallelism = 2;
        int boltParallelism = 2;
        int numBolts = 3;
        double cpuPercent = 10;
        double memoryOnHeap = 10;
        double memoryOffHeap = 10;
        double sharedOnHeap = 500;
        double sharedOffHeapNode = 700;
        double sharedOffHeapWorker = 500;
        TopologyBuilder builder = new TopologyBuilder();
        builder.setSpout("spout", new TestSpout(),
                spoutParallelism).addResource("gpu.count", 1.0);
        builder.setBolt("bolt-1", new TestBolt(),
                boltParallelism).addSharedMemory(new SharedOffHeapWithinWorker(sharedOffHeapWorker, "bolt-1 shared off heap worker")).shuffleGrouping("spout");
        builder.setBolt("bolt-2", new TestBolt(),
                boltParallelism).addSharedMemory(new SharedOffHeapWithinNode(sharedOffHeapNode, "bolt-2 shared node")).shuffleGrouping("bolt-1");
        builder.setBolt("bolt-3", new TestBolt(),
                boltParallelism).addSharedMemory(new SharedOnHeap(sharedOnHeap, "bolt-3 shared worker")).shuffleGrouping("bolt-2");

        StormTopology stormToplogy = builder.createTopology();

        INimbus iNimbus = new INimbusTest();

        Config conf = createGrasClusterConfig(cpuPercent, memoryOnHeap, memoryOffHeap, null, Collections.emptyMap());
        Map<String, Double> genericResourcesMap = new HashMap<>();
        genericResourcesMap.put("gpu.count", 1.0);

        Map<String, SupervisorDetails> supMap = genSupervisors(4, 4, 500, 2000, genericResourcesMap);

        conf.put(Config.TOPOLOGY_PRIORITY, 0);
        conf.put(Config.TOPOLOGY_NAME, "testTopology");
        conf.put(Config.TOPOLOGY_WORKER_MAX_HEAP_SIZE_MB, 2000);
        TopologyDetails topo = new TopologyDetails("testTopology-id", conf, stormToplogy, 0,
                genExecsAndComps(stormToplogy), currentTime, "user");

        Topologies topologies = new Topologies(topo);

        Cluster cluster = new Cluster(iNimbus, new ResourceMetrics(new StormMetricsRegistry()), supMap, new HashMap<>(), topologies, conf);

        scheduler = new ResourceAwareScheduler();

        scheduler.prepare(conf);
        scheduler.schedule(topologies, cluster);
        
        for (Entry<String, SupervisorResources> entry: cluster.getSupervisorsResourcesMap().entrySet()) {
            String supervisorId = entry.getKey();
            SupervisorResources resources = entry.getValue();
            assertTrue(supervisorId, resources.getTotalCpu() >= resources.getUsedCpu());
            assertTrue(supervisorId, resources.getTotalMem() >= resources.getUsedMem());
        }

        // If we didn't take GPUs into account everything would fit under a single slot
        // But because there is only 1 GPU per node, and each of the 2 spouts needs a GPU
        // It has to be scheduled on at least 2 nodes, and hence 2 slots.
        // Because of this all of the bolts will be scheduled on a single slot with one of
        // the spouts and the other spout is on its own slot.  So everything that can be shared is
        // shared.
        int totalNumberOfTasks = (spoutParallelism + (boltParallelism * numBolts));
        double totalExpectedCPU = totalNumberOfTasks * cpuPercent;
        double totalExpectedOnHeap = (totalNumberOfTasks * memoryOnHeap) + sharedOnHeap;
        double totalExpectedWorkerOffHeap = (totalNumberOfTasks * memoryOffHeap) + sharedOffHeapWorker;
        
        SchedulerAssignment assignment = cluster.getAssignmentById(topo.getId());
        Set<WorkerSlot> slots = assignment.getSlots();
        Map<String, Double> nodeToTotalShared = assignment.getNodeIdToTotalSharedOffHeapMemory();
        LOG.info("NODE TO SHARED OFF HEAP {}", nodeToTotalShared);
        Map<WorkerSlot, WorkerResources> scheduledResources = assignment.getScheduledResources();
        assertEquals(2, slots.size());
        assertEquals(2, nodeToTotalShared.size());
        assertEquals(2, scheduledResources.size());
        double totalFoundCPU = 0.0;
        double totalFoundOnHeap = 0.0;
        double totalFoundWorkerOffHeap = 0.0;
        for (WorkerSlot ws : slots) {
            WorkerResources resources = scheduledResources.get(ws);
            totalFoundCPU += resources.get_cpu();
            totalFoundOnHeap += resources.get_mem_on_heap();
            totalFoundWorkerOffHeap += resources.get_mem_off_heap();
        }

        assertEquals(totalExpectedCPU, totalFoundCPU, 0.01);
        assertEquals(totalExpectedOnHeap, totalFoundOnHeap, 0.01);
        assertEquals(totalExpectedWorkerOffHeap, totalFoundWorkerOffHeap, 0.01);
        assertEquals(sharedOffHeapNode, nodeToTotalShared.values().stream().mapToDouble((d) -> d).sum(), 0.01);
        assertEquals(sharedOnHeap, scheduledResources.values().stream().mapToDouble(WorkerResources::get_shared_mem_on_heap).sum(), 0.01);
        assertEquals(sharedOffHeapWorker, scheduledResources.values().stream().mapToDouble(WorkerResources::get_shared_mem_off_heap).sum(),
            0.01);
    }

    /**
     * test if the scheduling logic for the GenericResourceAwareStrategy is correct.
     */
    @Test
    public void testGenericResourceAwareStrategy() {
        int spoutParallelism = 1;
        int boltParallelism = 2;
        TopologyBuilder builder = new TopologyBuilder();
        builder.setSpout("spout", new TestSpout(),
                spoutParallelism);
        builder.setBolt("bolt-1", new TestBolt(),
                boltParallelism).shuffleGrouping("spout");
        builder.setBolt("bolt-2", new TestBolt(),
                boltParallelism).shuffleGrouping("bolt-1").addResource("gpu.count", 1.0);
        builder.setBolt("bolt-3", new TestBolt(),
                boltParallelism).shuffleGrouping("bolt-2").addResource("gpu.count", 2.0);

        StormTopology stormToplogy = builder.createTopology();

        INimbus iNimbus = new INimbusTest();

        Config conf = createGrasClusterConfig(50, 250, 250, null, Collections.emptyMap());
        Map<String, Double> genericResourcesMap = new HashMap<>();
        genericResourcesMap.put("gpu.count", 2.0);
        Map<String, SupervisorDetails> supMap = genSupervisors(4, 4, 150, 1500, genericResourcesMap);


        conf.put(Config.TOPOLOGY_PRIORITY, 0);
        conf.put(Config.TOPOLOGY_NAME, "testTopology");
        conf.put(Config.TOPOLOGY_WORKER_MAX_HEAP_SIZE_MB, Double.MAX_VALUE);
        conf.put(Config.TOPOLOGY_SUBMITTER_USER, "user");

        TopologyDetails topo = new TopologyDetails("testTopology-id", conf, stormToplogy, 0,
                genExecsAndComps(stormToplogy), currentTime, "user");

        Topologies topologies = new Topologies(topo);
        Cluster cluster = new Cluster(iNimbus, new ResourceMetrics(new StormMetricsRegistry()), supMap, new HashMap<>(), topologies, conf);

        scheduler = new ResourceAwareScheduler();

        scheduler.prepare(conf);
        scheduler.schedule(topologies, cluster);

        //We need to have 3 slots on 3 separate hosts. The topology needs 6 GPUs 3500 MB memory and 350% CPU
        // The bolt-3 instances must be on separate nodes because they each need 2 GPUs.
        // The bolt-2 instances must be on the same node as they each need 1 GPU
        // (this assumes that we are packing the components to avoid fragmentation).
        // The bolt-1 and spout instances fill in the rest.

        HashSet<HashSet<ExecutorDetails>> expectedScheduling = new HashSet<>();
        expectedScheduling.add(new HashSet<>(Arrays.asList(new ExecutorDetails(3, 3)))); //bolt-3 - 500 MB, 50% CPU, 2 GPU
        //Total 500 MB, 50% CPU, 2 - GPU -> this node has 1000 MB, 100% cpu, 0 GPU left
        expectedScheduling.add(new HashSet<>(Arrays.asList(
            new ExecutorDetails(2, 2), //bolt-1 - 500 MB, 50% CPU, 0 GPU
            new ExecutorDetails(5, 5), //bolt-2 - 500 MB, 50% CPU, 1 GPU
            new ExecutorDetails(6, 6)))); //bolt-2 - 500 MB, 50% CPU, 1 GPU
        //Total 1500 MB, 150% CPU, 2 GPU -> this node has 0 MB, 0% CPU, 0 GPU left
        expectedScheduling.add(new HashSet<>(Arrays.asList(
            new ExecutorDetails(0, 0), //Spout - 500 MB, 50% CPU, 0 GPU
            new ExecutorDetails(1, 1), //bolt-1 - 500 MB, 50% CPU, 0 GPU
            new ExecutorDetails(4, 4)))); //bolt-3 500 MB, 50% cpu, 2 GPU
        //Total 1500 MB, 150% CPU, 2 GPU -> this node has 0 MB, 0% CPU, 0 GPU left
        HashSet<HashSet<ExecutorDetails>> foundScheduling = new HashSet<>();
        SchedulerAssignment assignment = cluster.getAssignmentById("testTopology-id");
        for (Collection<ExecutorDetails> execs : assignment.getSlotToExecutors().values()) {
            foundScheduling.add(new HashSet<>(execs));
        }

        assertEquals(expectedScheduling, foundScheduling);
    }

    @Test
    public void testAntiAffinityWithMultipleTopologies() {
        INimbus iNimbus = new INimbusTest();
        Map<String, SupervisorDetails> supMap = genSupervisorsWithRacks(1, 40, 66, 0, 0, 4700, 226200, new HashMap<>());
        HashMap<String, Double> extraResources = new HashMap<>();
        extraResources.put("my.gpu", 1.0);
        supMap.putAll(genSupervisorsWithRacks(1, 40, 66, 1, 0, 4700, 226200, extraResources));

        Config config = new Config();
        config.putAll(createGrasClusterConfig(88, 775, 25, null, null));

        scheduler = new ResourceAwareScheduler();
        scheduler.prepare(config);

        TopologyDetails tdSimple = genTopology("topology-simple", config, 1,
            5, 100, 300, 0, 0, "user", 8192);

        //Schedule the simple topology first
        Topologies topologies = new Topologies(tdSimple);
        Cluster cluster = new Cluster(iNimbus, new ResourceMetrics(new StormMetricsRegistry()), supMap, new HashMap<>(), topologies, config);
        scheduler.schedule(topologies, cluster);

        TopologyBuilder builder = topologyBuilder(1, 5, 100, 300);
        builder.setBolt("gpu-bolt", new TestBolt(), 40)
            .addResource("my.gpu", 1.0)
            .shuffleGrouping("spout-0");
        TopologyDetails tdGpu = topoToTopologyDetails("topology-gpu", config, builder.createTopology(), 0, 0,"user", 8192);

        //Now schedule GPU but with the simple topology in place.
        topologies = new Topologies(tdSimple, tdGpu);
        cluster = new Cluster(cluster, topologies);
        scheduler.schedule(topologies, cluster);

        Map<String, SchedulerAssignment> assignments = new TreeMap<>(cluster.getAssignments());
        assertEquals(2, assignments.size());

        Map<String, Map<String, AtomicLong>> topoPerRackCount = new HashMap<>();
        for (Entry<String, SchedulerAssignment> entry: assignments.entrySet()) {
            SchedulerAssignment sa = entry.getValue();
            Map<String, AtomicLong> slotsPerRack = new TreeMap<>();
            for (WorkerSlot slot : sa.getSlots()) {
                String nodeId = slot.getNodeId();
                String rack = supervisorIdToRackName(nodeId);
                slotsPerRack.computeIfAbsent(rack, (r) -> new AtomicLong(0)).incrementAndGet();
            }
            LOG.info("{} => {}", entry.getKey(), slotsPerRack);
            topoPerRackCount.put(entry.getKey(), slotsPerRack);
        }

        Map<String, AtomicLong> simpleCount = topoPerRackCount.get("topology-simple-0");
        assertNotNull(simpleCount);
        //Because the simple topology was scheduled first we want to be sure that it didn't put anything on
        // the GPU nodes.
        assertEquals(1, simpleCount.size()); //Only 1 rack is in use
        assertFalse(simpleCount.containsKey("r001")); //r001 is the second rack with GPUs
        assertTrue(simpleCount.containsKey("r000")); //r000 is the first rack with no GPUs

        //We don't really care too much about the scheduling of topology-gpu-0, because it was scheduled.
    }
}
