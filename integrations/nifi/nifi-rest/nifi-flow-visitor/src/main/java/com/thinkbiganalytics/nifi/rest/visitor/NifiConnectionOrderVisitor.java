package com.thinkbiganalytics.nifi.rest.visitor;


import com.thinkbiganalytics.nifi.rest.client.NifiComponentNotFoundException;
import com.thinkbiganalytics.nifi.rest.client.NifiFlowVisitorClient;
import com.thinkbiganalytics.nifi.rest.model.visitor.NifiFlowVisitor;
import com.thinkbiganalytics.nifi.rest.model.visitor.NifiVisitableConnection;
import com.thinkbiganalytics.nifi.rest.model.visitor.NifiVisitableProcessGroup;
import com.thinkbiganalytics.nifi.rest.model.visitor.NifiVisitableProcessor;
import com.thinkbiganalytics.nifi.rest.support.NifiConnectionUtil;
import com.thinkbiganalytics.nifi.rest.support.NifiProcessUtil;

import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.web.api.dto.ConnectableDTO;
import org.apache.nifi.web.api.dto.ConnectionDTO;
import org.apache.nifi.web.api.dto.ProcessGroupDTO;
import org.apache.nifi.web.api.dto.ProcessorDTO;
import org.apache.nifi.web.api.entity.ProcessGroupEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Created by sr186054 on 2/14/16.
 */
public class NifiConnectionOrderVisitor implements NifiFlowVisitor {

    private static final Logger log = LoggerFactory.getLogger(NifiConnectionOrderVisitor.class);

    private NifiVisitableProcessGroup currentProcessGroup;

    private NifiVisitableProcessGroup processGroup;

    private Map<String, ProcessorDTO> processorsMap = new HashMap<>();

    private Map<String, NifiVisitableProcessor> visitedProcessors = new HashMap<>();

    /**
     * Cached map of the ConnectionId to a list of all processors that this connection is coming from
     * of which the connection is indicated as being a "failure"
     *
     * used for lookups to determine if an event has failed or not
     */
    private Map<String,Set<String>> failureConnectionIdToSourceProcessorIds = new HashMap<>();


    private Map<String, NifiVisitableProcessGroup> visitedProcessGroups = new HashMap<>();

    private Set<NifiVisitableConnection> allConnections = new HashSet<>();

    private NifiFlowVisitorClient restClient;


    public NifiConnectionOrderVisitor(NifiFlowVisitorClient restClient, NifiVisitableProcessGroup processGroup) {
        this.restClient = restClient;
        this.processGroup = processGroup;
        this.currentProcessGroup = processGroup;
        this.processorsMap = NifiProcessUtil.getProcessorsMap(processGroup.getDto());
    }

    @Override
    public void visitProcessor(NifiVisitableProcessor processor) {

        visitedProcessors.put(processor.getDto().getId(), processor);
        //add the pointer to the ProcessGroup
        currentProcessGroup.addProcessor(processor);
    }

    @Override
    public NifiVisitableProcessor getProcessor(String id) {
        return visitedProcessors.get(id);
    }

    @Override
    public NifiVisitableProcessGroup getProcessGroup(String id) {
        return visitedProcessGroups.get(id);
    }



    @Override
    public void visitConnection(NifiVisitableConnection connection) {
        Set<String> relationships = connection.getDto().getSelectedRelationships();
        String sourceType = connection.getDto().getSource().getType();
        String destType =  connection.getDto().getDestination().getType();

            List<NifiVisitableProcessor> destinationProcessors = getDestinationProcessors(connection.getDto(), true);

           List<NifiVisitableProcessor> sourceProcessors = getSourceProcessors(connection.getDto());


           //check to see if the destination connections are named "failure".  If so flag it as a potential failure processor
            if (destinationProcessors != null) {
                destinationProcessors.forEach(destinationProcessor -> destinationProcessor.addSourceConnectionIdentifier(connection.getDto()));
                if (relationships != null && relationships.contains("failure") && !relationships.contains("success") && (StringUtils.isBlank(connection.getDto().getName()) || (destType.equals("FUNNEL") && connection.getDto().getName().equalsIgnoreCase("failure")))) {
                    for(NifiVisitableProcessor destination:destinationProcessors) {
                        destination.setIsFailureProcessor(true);


                    }
                }
            }
            if (destinationProcessors != null && sourceProcessors != null) {
                for(NifiVisitableProcessor destination:destinationProcessors) {
                    for(NifiVisitableProcessor source: sourceProcessors){
                        destination.addSource(source);
                        if(destination.isFailureProcessor()) {
                            //save the source processor in the incoming failed connection id map
                            failureConnectionIdToSourceProcessorIds.computeIfAbsent(connection.getDto().getId(),(id) -> new HashSet<>()).add(source.getId());
                        }
                        source.addDestination(destination);
                    }
                }
            }

        for (NifiVisitableProcessor sourceProcessor : sourceProcessors) {
            sourceProcessor.addDestinationConnectionIdentifier(connection.getDto());
        }



        allConnections.add(connection);

    }

    @Override
    public Map<String, Set<String>> getFailureConnectionIdToSourceProcessorIds() {
        return failureConnectionIdToSourceProcessorIds;
    }

    @Override
    public void visitProcessGroup(NifiVisitableProcessGroup processGroup) {

        log.debug(" Visit Process Group: {}, ({}) ", processGroup.getDto().getName(), processGroup.getDto().getId());

        NifiVisitableProcessGroup group = visitedProcessGroups.get(processGroup.getDto().getId());

        if (group == null) {
            group = processGroup;
        }
        this.currentProcessGroup = group;
        group.accept(this);
        this.visitedProcessGroups.put(group.getDto().getId(), group);

    }


    public List<NifiVisitableProcessor> getDestinationProcessors(ConnectionDTO connection, boolean getSource) {
        ConnectableDTO dest = connection.getDestination();
        List<NifiVisitableProcessor> destinationProcessors = new ArrayList<>();
        if (dest != null) {

            if ("INPUT_PORT".equalsIgnoreCase(dest.getType())) {
                boolean isNew = false;
                NifiVisitableProcessGroup group = visitedProcessGroups.get(dest.getGroupId());
                if (group == null) {
                    group = fetchProcessGroup(dest.getGroupId());
                }
                ConnectionDTO conn = group.getConnectionMatchingSourceId(dest.getId());
                if (conn != null) {
                    destinationProcessors = getDestinationProcessors(conn, getSource);

                    if (getSource) {
                        List<NifiVisitableProcessor> outputProcessors = getSourceProcessors(connection);
                        if (outputProcessors != null) {
                            for(NifiVisitableProcessor outputProcessor: outputProcessors) {
                                //outputProcessor.setOutputPortId(dest.getId());
                                currentProcessGroup.addOutputPortProcessor(dest.getId(), outputProcessor);
                            }
                        }
                    }
                }


            } else if ("OUTPUT_PORT".equals(dest.getType())) {
                boolean isNew = false;
                //get parent processgroup connection to input port
                NifiVisitableProcessGroup group = visitedProcessGroups.get(dest.getGroupId());
                if (group == null) {
                    group = fetchProcessGroup(dest.getGroupId());
                }
                ConnectionDTO conn = group.getConnectionMatchingSourceId(dest.getId());
                if (conn == null) {
                    conn = searchConnectionMatchingSource(group.getDto().getParentGroupId(), dest.getId());
                }
                if (conn != null) {
                    //get the processor whos source matches this connection Id
                    List<NifiVisitableProcessor> destinations = getDestinationProcessors(conn, getSource);
                    if (destinations != null) {
                        destinationProcessors.addAll(destinations);
                    }
                    if (getSource) {
                        List<NifiVisitableProcessor> outputProcessors = getSourceProcessors(connection);
                        if (outputProcessors != null) {
                            for(NifiVisitableProcessor outputProcessor: outputProcessors) {
                                currentProcessGroup.addOutputPortProcessor(dest.getId(), outputProcessor);
                            }
                        }
                    }
                }

            }
            else if("FUNNEL".equals(dest.getType())) {
                List<ConnectionDTO>
                    passThroughConnections =
                    NifiConnectionUtil.findConnectionsMatchingSourceId(currentProcessGroup.getDto().getContents().getConnections(), connection.getDestination().getId());
                if (passThroughConnections != null) {
                    for (ConnectionDTO dto : passThroughConnections) {
                        ConnectionDTO newConnection = new ConnectionDTO();
                        newConnection.setId(connection.getSource().getId());
                        newConnection.setSource(connection.getSource());
                        newConnection.setDestination(dto.getDestination());
                        List<NifiVisitableProcessor> destinations  = getDestinationProcessors(newConnection, getSource);
                        if (destinations != null) {
                            //  destinationProcessor.setOutputPortId(dest.getId());
                            destinationProcessors.addAll(destinations);
                        }
                    }
                }
            }
            else if ("PROCESSOR".equals(dest.getType())) {
              NifiVisitableProcessor  destinationProcessor = getConnectionProcessor(dest.getGroupId(), dest.getId());
                destinationProcessors.add(destinationProcessor);
            }
        }
        for (NifiVisitableProcessor destinationProcessor : destinationProcessors) {
            destinationProcessor.addSourceConnectionIdentifier(connection);
        }
        return destinationProcessors;
    }


    public List<NifiVisitableProcessor> getSourceProcessors(ConnectionDTO connection) {

        ConnectableDTO source = connection.getSource();
        List<NifiVisitableProcessor> sourceProcessors = new ArrayList<>();
        if (source != null) {
            if ("INPUT_PORT".equalsIgnoreCase(source.getType())) {
                NifiVisitableProcessGroup group = visitedProcessGroups.get(source.getGroupId());
                if (group == null) {
                    group = processGroup;
                }
                NifiVisitableProcessGroup parent = visitedProcessGroups.get(group.getDto().getParentGroupId());
                //if the parent is null the parent is the starting process group
                if (parent == null) {
                    parent = processGroup;
                }

                ConnectionDTO conn = parent.getConnectionMatchingDestinationId(source.getId());
                if (conn != null && conn != connection) {
                    //get the processor whos source matches this connection Id
                    sourceProcessors = getSourceProcessors(conn);
                    //assign the inputPortProcessor == the the destination of this connection
                }
                List<NifiVisitableProcessor> inputProcessors = getDestinationProcessors(connection, false);
                if(inputProcessors != null) {
                    for(NifiVisitableProcessor inputProcessor: inputProcessors) {
                        //   inputProcessor.addInputPortId(source.getId(), );
                        currentProcessGroup.addInputPortProcessor(source.getId(), inputProcessor);
                    }
                }

            } else if ("OUTPUT_PORT".equals(source.getType())) {
                //get the sources group id then get the ending processor for that group
                NifiVisitableProcessGroup group = visitedProcessGroups.get(source.getGroupId());
                if (group != null) {
                    Set<NifiVisitableProcessor> sources = group.getOutputPortProcessors(source.getId());
                    if(sourceProcessors != null) {
                        sourceProcessors.addAll(sources);
                    }
                }
            }
            else if("FUNNEL".equalsIgnoreCase(source.getType())) {
                List<ConnectionDTO> passThroughConnections = NifiConnectionUtil.findConnectionsMatchingDestinationId(currentProcessGroup.getDto().getContents().getConnections(),
                                                                                                                     connection.getSource().getId());
                if(passThroughConnections != null) {
                    for(ConnectionDTO dto: passThroughConnections){
                        ConnectionDTO newConnection = new ConnectionDTO();
                        newConnection.setSource(dto.getSource());
                        newConnection.setId(connection.getSource().getId());
                        newConnection.setDestination(connection.getDestination());
                        visitConnection(new NifiVisitableConnection(currentProcessGroup, newConnection));
                    }
                }

            }
            else if ("PROCESSOR".equals(source.getType())) {
                NifiVisitableProcessor sourceProcessor = getConnectionProcessor(source.getGroupId(), source.getId());
                sourceProcessors.add(sourceProcessor);
            }
            for (NifiVisitableProcessor sourceProcessor : sourceProcessors) {
                sourceProcessor.addDestinationConnectionIdentifier(connection);
            }
        }
        return sourceProcessors;
    }


    private NifiVisitableProcessGroup fetchProcessGroup(String groupId) {
        NifiVisitableProcessGroup group = processGroup;
        //fetch it
        ProcessGroupDTO processGroupEntity = null;
        try {
            try {
                log.debug("fetchProcessGroup {} ", groupId);
                processGroupEntity = restClient.getProcessGroup(groupId, false, true);
            } catch (NifiComponentNotFoundException e) {
                log.debug("Unable to find the process group " + groupId);
            }
            //if the parent is null the parent is the starting process group
            if (processGroupEntity != null) {
                group = new NifiVisitableProcessGroup(processGroupEntity);
            }
        } catch (Exception e) {
            log.error("Exception fetching the process group " + groupId);
        }
        return group;
    }


    private ConnectionDTO searchConnectionMatchingSource(String parentGroupId, String destinationId) {
        //search up to find the connection that matches this dest id

        try {
            ProcessGroupDTO parent = null;
            try {
                log.debug("fetch ProcessGroup for searchConnectionMatchingSource {} ", parentGroupId);
                parent = restClient.getProcessGroup(parentGroupId, false, true);
            } catch (NifiComponentNotFoundException e) {
                log.debug("Exception searching Connection matching the source. Parent Group ID: " + parentGroupId + ", and destinationId of  " + destinationId);
            }
            if (parent != null) {
                //processGroup.getDto().setParent(parentParent.getProcessGroup());
                //get Contents of this parent
                NifiVisitableProcessGroup visitableProcessGroup = new NifiVisitableProcessGroup(parent);
                ConnectionDTO conn = visitableProcessGroup.getConnectionMatchingSourceId(destinationId);
                if (conn != null) {
                    return conn;
                }
                if (conn == null && parent.getParentGroupId() != null) {
                    return searchConnectionMatchingSource(parent.getParentGroupId(), destinationId);
                }
            }

        } catch (Exception e) {
            log.error("Exception searching Connection matching the source.  Parent Group ID: " + parentGroupId + ", and destinationId of  " + destinationId);
        }
        return null;

    }

    private ConnectionDTO searchConnectionMatchingDestination(String parentGroupId, String sourceId) {
        //search up to find the connectoin that matches this dest id

        try {
            ProcessGroupDTO parent = null;
            try {
                parent = restClient.getProcessGroup(parentGroupId, false, true);
            } catch (NifiComponentNotFoundException e) {
                log.debug("Exception searching Connection matching the destination. Parent Group ID: " + parentGroupId + ", and destinationId of  " + sourceId);
            }
            if (parent != null) {
                //processGroup.getDto().setParent(parentParent.getProcessGroup());
                //get Contents of this parent
                NifiVisitableProcessGroup visitableProcessGroup = new NifiVisitableProcessGroup(parent);
                ConnectionDTO conn = visitableProcessGroup.getConnectionMatchingDestinationId(sourceId);
                if (conn != null) {
                    return conn;
                }
                if (conn == null && parent.getParentGroupId() != null) {
                    return searchConnectionMatchingSource(parent.getParentGroupId(), sourceId);
                }
            }

        } catch (Exception e) {
            log.error("Exception searching Connection matching the destination.  Parent Group ID: " + parentGroupId + ", and source of  " + sourceId);
        }
        return null;

    }







    private NifiVisitableProcessor getConnectionProcessor(String groupId, String id) {
        NifiVisitableProcessor processor = visitedProcessors.get(id);
        if (processor == null) {
            if (!this.processorsMap.containsKey(id)) {
                //if the current group is not related to this processgroup then attempt to walk this processors processgroup
                try {
                    log.debug("fetch ProcessGroup for getConnectionProcessor {} ", groupId);
                    ProcessGroupDTO processGroupEntity = restClient.getProcessGroup(groupId, false, true);
                    ProcessorDTO processorDTO = NifiProcessUtil.findFirstProcessorsById(
                        processGroupEntity.getContents().getProcessors(), id);
                    if (processorDTO != null) {
                        this.processorsMap.put(id, processorDTO);
                    }
                    if (processGroup.getDto().getId() != groupId && !visitedProcessGroups.containsKey(processGroupEntity.getId())) {
                        visitProcessGroup(new NifiVisitableProcessGroup(processGroupEntity));
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            //
            processor = visitedProcessors.get(id);
            if (processor == null) {
                processor = new NifiVisitableProcessor(this.processorsMap.get(id));
                //visit the group?
                processor.accept(this);
            }


        }
        return processor;
    }


    public Integer getNumberOfSplits() {
        int count = 0;
        for (NifiVisitableProcessor processor : visitedProcessors.values()) {
            Set<NifiVisitableProcessor> destinations = processor.getDestinations();
            if (destinations != null && !destinations.isEmpty()) {
                count += (destinations.size() - 1);
            }
        }
        return count;
    }

    /**
     * inspect the current status and determine if it has data in queue
     */
    public boolean isProcessingData() {
        return false;
    }

    public void printOrder() {
        for (NifiVisitableProcessor processor : processGroup.getStartingProcessors()) {
            processor.print();
        }
    }


}
