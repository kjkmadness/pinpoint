
/*
 * Copyright 2014 NAVER Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.navercorp.pinpoint.web.service;

import com.navercorp.pinpoint.common.Version;
import com.navercorp.pinpoint.common.server.util.AgentEventType;
import com.navercorp.pinpoint.common.server.util.AgentLifeCycleState;
import com.navercorp.pinpoint.rpc.util.ListUtils;
import com.navercorp.pinpoint.web.dao.AgentDownloadInfoDao;
import com.navercorp.pinpoint.web.dao.AgentInfoDao;
import com.navercorp.pinpoint.web.dao.AgentLifeCycleDao;
import com.navercorp.pinpoint.web.dao.ApplicationIndexDao;
import com.navercorp.pinpoint.web.dao.stat.JvmGcDao;
import com.navercorp.pinpoint.web.filter.agent.AgentEventFilter;
import com.navercorp.pinpoint.web.service.stat.AgentWarningStatService;
import com.navercorp.pinpoint.web.vo.AgentDownloadInfo;
import com.navercorp.pinpoint.web.vo.AgentEvent;
import com.navercorp.pinpoint.web.vo.AgentInfo;
import com.navercorp.pinpoint.web.vo.AgentInfoFilter;
import com.navercorp.pinpoint.web.vo.AgentStatus;
import com.navercorp.pinpoint.web.vo.AgentStatusQuery;
import com.navercorp.pinpoint.web.vo.Application;
import com.navercorp.pinpoint.web.vo.ApplicationAgentHostList;
import com.navercorp.pinpoint.web.vo.ApplicationAgentsList;
import com.navercorp.pinpoint.web.vo.Range;
import com.navercorp.pinpoint.web.vo.timeline.inspector.AgentEventTimeline;
import com.navercorp.pinpoint.web.vo.timeline.inspector.AgentEventTimelineBuilder;
import com.navercorp.pinpoint.web.vo.timeline.inspector.AgentStatusTimeline;
import com.navercorp.pinpoint.web.vo.timeline.inspector.AgentStatusTimelineBuilder;
import com.navercorp.pinpoint.web.vo.timeline.inspector.AgentStatusTimelineSegment;
import com.navercorp.pinpoint.web.vo.timeline.inspector.InspectorTimeline;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * @author netspider
 * @author HyunGil Jeong
 */
@Service
public class AgentInfoServiceImpl implements AgentInfoService {

    private final Logger logger = LogManager.getLogger(this.getClass());

    private final AgentEventService agentEventService;

    private final AgentWarningStatService agentWarningStatService;

    private final ApplicationIndexDao applicationIndexDao;

    private final AgentInfoDao agentInfoDao;

    private final AgentLifeCycleDao agentLifeCycleDao;

    private final AgentDownloadInfoDao agentDownloadInfoDao;

    private final JvmGcDao jvmGcDao;

    public AgentInfoServiceImpl(AgentEventService agentEventService,
                                AgentWarningStatService agentWarningStatService, ApplicationIndexDao applicationIndexDao,
                                AgentInfoDao agentInfoDao, AgentLifeCycleDao agentLifeCycleDao,
                                AgentDownloadInfoDao agentDownloadInfoDao, @Qualifier("jvmGcDaoFactory") JvmGcDao jvmGcDao) {
        this.agentEventService = Objects.requireNonNull(agentEventService, "agentEventService");
        this.agentWarningStatService = Objects.requireNonNull(agentWarningStatService, "agentWarningStatService");
        this.applicationIndexDao = Objects.requireNonNull(applicationIndexDao, "applicationIndexDao");
        this.agentInfoDao = Objects.requireNonNull(agentInfoDao, "agentInfoDao");
        this.agentLifeCycleDao = Objects.requireNonNull(agentLifeCycleDao, "agentLifeCycleDao");
        this.agentDownloadInfoDao = Objects.requireNonNull(agentDownloadInfoDao, "agentDownloadInfoDao");
        this.jvmGcDao = Objects.requireNonNull(jvmGcDao, "jvmGcDao");
    }

    @Override
    public ApplicationAgentsList getAllApplicationAgentsList(AgentInfoFilter filter, long timestamp) {
        Objects.requireNonNull(filter, "filter");

        ApplicationAgentsList.GroupBy groupBy = ApplicationAgentsList.GroupBy.APPLICATION_NAME;
        ApplicationAgentsList applicationAgentList = new ApplicationAgentsList(groupBy, filter);
        List<Application> applications = applicationIndexDao.selectAllApplicationNames();
        for (Application application : applications) {
            applicationAgentList.merge(getApplicationAgentsList(groupBy, filter, application.getName(), timestamp));
        }
        return applicationAgentList;
    }

    @Override
    public ApplicationAgentsList getApplicationAgentsList(ApplicationAgentsList.GroupBy groupBy, AgentInfoFilter filter, String applicationName, long timestamp) {
        Objects.requireNonNull(groupBy, "groupBy");
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(applicationName, "applicationName");

        ApplicationAgentsList applicationAgentsList = new ApplicationAgentsList(groupBy, filter);
        Set<AgentInfo> agentInfos = getAgentsByApplicationName(applicationName, timestamp);
        if (agentInfos.isEmpty()) {
            logger.warn("agent list is empty for application:{}", applicationName);
            return applicationAgentsList;
        }
        applicationAgentsList.addAll(agentInfos);
        if (logger.isDebugEnabled()) {
            logger.debug("getApplicationAgentsList={}", applicationAgentsList);
        }
        return applicationAgentsList;
    }

    @Override
    public ApplicationAgentHostList getApplicationAgentHostList(int offset, int limit) {
        if (offset <= 0 || limit <= 0) {
            throw new IllegalArgumentException("Value must be greater than 0.");
        }

        return getApplicationAgentHostList0(offset, limit, -1);
    }

    @Override
    public ApplicationAgentHostList getApplicationAgentHostList(int offset, int limit, int durationDays) {
        if (offset <= 0 || limit <= 0) {
            throw new IllegalArgumentException("Value must be greater than 0.");
        }

        return getApplicationAgentHostList0(offset, limit, durationDays);
    }

    private ApplicationAgentHostList getApplicationAgentHostList0(int offset, int limit, int durationDays) {
        List<String> applicationNameList = getApplicationNameList(applicationIndexDao.selectAllApplicationNames());
        if (offset > applicationNameList.size()) {
            return new ApplicationAgentHostList(offset, offset, applicationNameList.size());
        }

        long timeStamp = System.currentTimeMillis();

        int startIndex = offset - 1;
        int endIndex = Math.min(startIndex + limit, applicationNameList.size());
        ApplicationAgentHostList applicationAgentHostList = new ApplicationAgentHostList(offset, endIndex, applicationNameList.size());
        for (int i = startIndex; i < endIndex; i++) {
            String applicationName = applicationNameList.get(i);

            List<String> agentIdList = getAgentIdList(applicationName, durationDays);
            List<AgentInfo> agentInfoList = this.agentInfoDao.getAgentInfos(agentIdList, timeStamp);
            applicationAgentHostList.put(applicationName, agentInfoList);
        }
        return applicationAgentHostList;
    }

    private List<String> getAgentIdList(String applicationName, int durationDays) {
        List<String> agentIds = this.applicationIndexDao.selectAgentIds(applicationName);
        if (CollectionUtils.isEmpty(agentIds)) {
            return Collections.emptyList();
        }

        if (durationDays <= 0) {
            return agentIds;
        }

        List<String> activeAgentIdList = new ArrayList<>();

        Range fastRange = Range.newRange(TimeUnit.HOURS, 1, System.currentTimeMillis());

        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DATE, durationDays * -1);
        final long fromTimestamp = cal.getTimeInMillis();
        Range queryRange = Range.newRange(fromTimestamp, fastRange.getFrom() + 1);

        for (String agentId : agentIds) {
            // FIXME This needs to be done with a more accurate information.
            // If at any time a non-java agent is introduced, or an agent that does not collect jvm data,
            // this will fail
            boolean dataExists = isActiveAgent(agentId, fastRange);
            if (dataExists) {
                activeAgentIdList.add(agentId);
                continue;
            }

            dataExists = isActiveAgent(agentId, queryRange);
            if (dataExists) {
                activeAgentIdList.add(agentId);
            }
        }
        return activeAgentIdList;
    }

    private List<String> getApplicationNameList(List<Application> applications) {
        List<String> applicationNameList = new ArrayList<>(applications.size());
        for (Application application : applications) {
            if (!applicationNameList.contains(application.getName())) {
                applicationNameList.add(application.getName());
            }
        }

        applicationNameList.sort(String::compareTo);
        return applicationNameList;
    }

    @Override
    public Set<AgentInfo> getAgentsByApplicationName(String applicationName, long timestamp) {
        List<AgentInfo> agentInfos = this.getAgentsByApplicationNameWithoutStatus0(applicationName, timestamp);


        AgentStatusQuery query = AgentStatusQuery.buildQuery(agentInfos, timestamp);
        List<Optional<AgentStatus>> agentStatus = this.agentLifeCycleDao.getAgentStatus(query);
        for (int i = 0; i < agentStatus.size(); i++) {
            Optional<AgentStatus> status = agentStatus.get(i);
            if (status.isPresent()) {
                AgentInfo agentInfo = agentInfos.get(i);
                agentInfo.setStatus(status.get());
            }
        }
        return new HashSet<>(agentInfos);
    }


    @Override
    public Set<AgentInfo> getAgentsByApplicationNameWithoutStatus(String applicationName, long timestamp) {
        List<AgentInfo> agentInfos = getAgentsByApplicationNameWithoutStatus0(applicationName, timestamp);
        return new HashSet<>(agentInfos);
    }

    public List<AgentInfo> getAgentsByApplicationNameWithoutStatus0(String applicationName, long timestamp) {
        Objects.requireNonNull(applicationName, "applicationName");
        if (timestamp < 0) {
            throw new IllegalArgumentException("timestamp must not be less than 0");
        }

        List<String> agentIds = this.applicationIndexDao.selectAgentIds(applicationName);
        List<AgentInfo> agentInfos = this.agentInfoDao.getAgentInfos(agentIds, timestamp);

        return agentInfos.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

    }

    @Override
    public Set<AgentInfo> getRecentAgentsByApplicationName(String applicationName, long timestamp, long timeDiff) {
        if (timeDiff > timestamp) {
            throw new IllegalArgumentException("timeDiff must not be greater than timestamp");
        }

        Set<AgentInfo> unfilteredAgentInfos = this.getAgentsByApplicationName(applicationName, timestamp);

        final long eventTimestampFloor = timestamp - timeDiff;

        Set<AgentInfo> filteredAgentInfos = new HashSet<>();
        for (AgentInfo agentInfo : unfilteredAgentInfos) {
            AgentStatus agentStatus = agentInfo.getStatus();
            if (AgentLifeCycleState.UNKNOWN == agentStatus.getState() || eventTimestampFloor <= agentStatus.getEventTimestamp()) {
                filteredAgentInfos.add(agentInfo);
            }
        }
        return filteredAgentInfos;
    }

    @Override
    public AgentInfo getAgentInfo(String agentId, long timestamp) {
        Objects.requireNonNull(agentId, "agentId");

        if (timestamp < 0) {
            throw new IllegalArgumentException("timestamp must not be less than 0");
        }
        AgentInfo agentInfo = this.agentInfoDao.getAgentInfo(agentId, timestamp);
        if (agentInfo != null) {
            Optional<AgentStatus> agentStatus = this.agentLifeCycleDao.getAgentStatus(agentInfo.getAgentId(), agentInfo.getStartTimestamp(), timestamp);
            agentInfo.setStatus(agentStatus.orElse(null));
        }
        return agentInfo;
    }

    @Override
    public AgentInfo getAgentInfoNoStatus(String agentId, long agentStartTime, int deltaTimeInMilliSeconds) {
        return this.agentInfoDao.getAgentInfo(agentId, agentStartTime, deltaTimeInMilliSeconds);
    }

    @Override
    public AgentStatus getAgentStatus(String agentId, long timestamp) {
        Objects.requireNonNull(agentId, "agentId");
        if (timestamp < 0) {
            throw new IllegalArgumentException("timestamp must not be less than 0");
        }
        return this.agentLifeCycleDao.getAgentStatus(agentId, timestamp);
    }

    @Override
    public List<Optional<AgentStatus>> getAgentStatus(AgentStatusQuery query) {
        Objects.requireNonNull(query, "query");
        if (query.getQueryTimestamp() < 0) {
            throw new IllegalArgumentException("timestamp must not be less than 0");
        }
        return this.agentLifeCycleDao.getAgentStatus(query);
    }

    @Override
    public boolean isActiveAgent(String agentId, Range range) {
        boolean dataExists = this.jvmGcDao.agentStatExists(agentId, range);
        if (dataExists) {
            return true;
        }

        List<AgentEvent> agentEvents = this.agentEventService.getAgentEvents(agentId, range);
        return agentEvents.stream().anyMatch(e -> e.getEventTypeCode() == AgentEventType.AGENT_PING.getCode());
    }

    @Override
    public InspectorTimeline getAgentStatusTimeline(String agentId, Range range, int... excludeAgentEventTypeCodes) {
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(range, "range");

        AgentStatus initialStatus = getAgentStatus(agentId, range.getFrom());
        List<AgentEvent> agentEvents = agentEventService.getAgentEvents(agentId, range);

        List<AgentStatusTimelineSegment> warningStatusTimelineSegmentList = agentWarningStatService.select(agentId, range);

        AgentStatusTimelineBuilder agentStatusTimelinebuilder = new AgentStatusTimelineBuilder(range, initialStatus, agentEvents, warningStatusTimelineSegmentList);
        AgentStatusTimeline agentStatusTimeline = agentStatusTimelinebuilder.build();

        AgentEventTimelineBuilder agentEventTimelineBuilder = new AgentEventTimelineBuilder(range);
        agentEventTimelineBuilder.from(agentEvents);
        agentEventTimelineBuilder.addFilter(new AgentEventFilter.ExcludeFilter(excludeAgentEventTypeCodes));
        AgentEventTimeline agentEventTimeline = agentEventTimelineBuilder.build();

        return new InspectorTimeline(agentStatusTimeline, agentEventTimeline);
    }

    @Override
    public boolean isExistAgentId(String agentId) {
        AgentInfo agentInfo = getAgentInfo(agentId, System.currentTimeMillis());
        return agentInfo != null;
    }

    private volatile AgentDownloadInfo cachedAgentDownloadInfo;

    private static final Comparator<AgentDownloadInfo> REVERSE = Comparator.comparing(AgentDownloadInfo::getVersion).reversed();

    @Override
    public AgentDownloadInfo getLatestStableAgentDownloadInfo() {
        if (cachedAgentDownloadInfo != null) {
            return cachedAgentDownloadInfo;
        }

        List<AgentDownloadInfo> downloadInfoList = agentDownloadInfoDao.getDownloadInfoList();
        if (CollectionUtils.isEmpty(downloadInfoList)) {
            return null;
        }

        downloadInfoList.sort(REVERSE);

        // 1st. find same
        for (AgentDownloadInfo downloadInfo : downloadInfoList) {
            if (Version.VERSION.equals(downloadInfo.getVersion())) {
                cachedAgentDownloadInfo = downloadInfo;
                return downloadInfo;
            }
        }

        // 2nd. find lower
        for (AgentDownloadInfo downloadInfo : downloadInfoList) {
            if (Version.VERSION.compareTo(downloadInfo.getVersion()) > 0) {
                cachedAgentDownloadInfo = downloadInfo;
                return downloadInfo;
            }
        }

        // 3rd find greater
        AgentDownloadInfo downloadInfo = ListUtils.getLast(downloadInfoList);
        cachedAgentDownloadInfo = downloadInfo;
        return downloadInfo;
    }

}
