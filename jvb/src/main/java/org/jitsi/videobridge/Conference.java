/*
 * Copyright @ 2015 - Present, 8x8 Inc
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
package org.jitsi.videobridge;

import kotlin.*;
import org.jetbrains.annotations.*;
import org.jitsi.nlj.*;
import org.jitsi.nlj.rtp.*;
import org.jitsi.rtp.Packet;
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.*;
import org.jitsi.rtp.rtp.*;
import org.jitsi.utils.collections.*;
import org.jitsi.utils.dsi.*;
import org.jitsi.utils.logging.*;
import org.jitsi.utils.logging2.Logger;
import org.jitsi.utils.logging2.LoggerImpl;
import org.jitsi.utils.logging2.*;
import org.jitsi.utils.queue.*;
import org.jitsi.videobridge.colibri2.*;
import org.jitsi.videobridge.message.*;
import org.jitsi.videobridge.octo.*;
import org.jitsi.videobridge.relay.*;
import org.jitsi.videobridge.shim.*;
import org.jitsi.videobridge.util.*;
import org.jitsi.videobridge.xmpp.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.colibri2.*;
import org.jitsi.xmpp.util.*;
import org.jivesoftware.smack.packet.*;
import org.json.simple.*;
import org.jxmpp.jid.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.*;

import static org.jitsi.utils.collections.JMap.*;

/**
 * Represents a conference in the terms of Jitsi Videobridge.
 *
 * @author Lyubomir Marinov
 * @author Boris Grozev
 * @author Hristo Terezov
 * @author George Politis
 */
public class Conference
     implements AbstractEndpointMessageTransport.EndpointMessageTransportEventHandler
{
    /**
     * The constant denoting that {@link #gid} is not specified.
     */
    public static final long GID_NOT_SET = -1;

    /**
     * The special GID value used when signaling uses colibri2. With colibri2 the GID field is not required.
     */
    public static final long GID_COLIBRI2 = -2;

    /**
     * The endpoints participating in this {@link Conference}. Although it's a
     * {@link ConcurrentHashMap}, writing to it must be protected by
     * synchronizing on the map itself, because it must be kept in sync with
     * {@link #endpointsCache}.
     */
    private final ConcurrentHashMap<String, AbstractEndpoint> endpointsById = new ConcurrentHashMap<>();

    /**
     * A boolean that indicates whether or not to include RTCStats for this call.
     */
    private final boolean isRtcStatsEnabled;

    /**
     * A boolean that indicates whether or not to report to CallStats for this call.
     */
    private final boolean isCallStatsEnabled;

    /**
     * A read-only cache of the endpoints in this conference. Note that it
     * contains only the {@link Endpoint} instances (local endpoints, not Octo endpoints).
     * This is because the cache was introduced for performance reasons only
     * (we iterate over it for each RTP packet) and the Octo endpoints are not
     * needed.
     */
    private List<Endpoint> endpointsCache = Collections.emptyList();

    private final Object endpointsCacheLock = new Object();

    /**
     * The relays participating in this conference.
     */
    private final ConcurrentHashMap<String, Relay> relaysById = new ConcurrentHashMap<>();

    /**
     * The indicator which determines whether {@link #expire()} has been called
     * on this <tt>Conference</tt>.
     */
    private final AtomicBoolean expired = new AtomicBoolean(false);

    public long getLocalAudioSsrc()
    {
        return localAudioSsrc;
    }

    public long getLocalVideoSsrc()
    {
        return localVideoSsrc;
    }

    private final long localAudioSsrc = Videobridge.RANDOM.nextLong() & 0xffff_ffffL;
    private final long localVideoSsrc = Videobridge.RANDOM.nextLong() & 0xffff_ffffL;

    /**
     * The locally unique identifier of this conference (i.e. unique across the
     * conferences on this bridge). It is locally generated and exposed via
     * colibri, and used by colibri clients to reference the conference.
     *
     * The current thinking is that we will phase out use of this ID (but keep
     * it for backward compatibility) in favor of {@link #gid}.
     */
    private final String id;

    /**
     * The "global" id of this conference, selected by the controller of the
     * bridge (e.g. jicofo). The set of controllers are responsible for making
     * sure this ID is unique across all conferences in the system.
     *
     * This is not a required field unless Octo is used, and when it is not
     * specified by the controller its value is {@link #GID_NOT_SET}.
     *
     * If specified, it must be a 32-bit unsigned integer (i.e. in the range
     * [0, 0xffff_ffff]).
     *
     * This ID is shared between all bridges in an Octo conference, and included
     * on-the-wire in Octo packets.
     */
    private final long gid;

    /**
     * The world readable name of this instance if any.
     */
    private final @Nullable EntityBareJid conferenceName;

    /**
     * The speech activity (representation) of the <tt>Endpoint</tt>s of this
     * <tt>Conference</tt>.
     */
    private final ConferenceSpeechActivity speechActivity;

    /**
     * The <tt>Videobridge</tt> which has initialized this <tt>Conference</tt>.
     */
    private final Videobridge videobridge;

    /**
     * Holds conference statistics.
     */
    private final Statistics statistics = new Statistics();

    /**
     * The {@link Logger} to be used by this instance to print debug
     * information.
     */
    private final Logger logger;

    /**
     * The time when this {@link Conference} was created.
     */
    private final long creationTime = System.currentTimeMillis();

    /**
     * The shim which handles Colibri-related logic for this conference (translates colibri v1 signaling into the
     * native videobridge APIs).
     */
    @NotNull private final ConferenceShim shim;

    /**
     * The shim which handles Colibri v2 related logic for this conference (translates colibri v2 signaling into the
     * native videobridge APIs).
     */
    @NotNull private final Colibri2ConferenceHandler colibri2Handler;

    /**
     * The queue handling colibri (both v1 and v2) messages for this conference.
     */
    @NotNull private final PacketQueue<XmppConnection.ColibriRequest> colibriQueue;

    @NotNull private final EncodingsManager encodingsManager = new EncodingsManager();

    /**
     * This {@link Conference}'s link to Octo.
     */
    private ConfOctoTransport tentacle;

    /**
     * The task of updating the ordered list of endpoints in the conference. It runs periodically in order to adapt to
     * endpoints stopping or starting to their video streams (which affects the order).
     */
    private ScheduledFuture<?> updateLastNEndpointsFuture;

    @NotNull
    private final EndpointConnectionStatusMonitor epConnectionStatusMonitor;

    /**
     * A unique meeting ID optionally set by the signaling server ({@code null} if not explicitly set). It is exposed
     * via ({@link #getDebugState()} for outside use.
     */
    @Nullable
    private final String meetingId;

    /**
     * Initializes a new <tt>Conference</tt> instance which is to represent a
     * conference in the terms of Jitsi Videobridge which has a specific
     * (unique) ID.
     *
     * @param videobridge the <tt>Videobridge</tt> on which the new
     * <tt>Conference</tt> instance is to be initialized
     * @param id the (unique) ID of the new instance to be initialized
     * @param conferenceName world readable name of this conference
     * @param gid the optional "global" id of the conference.
     */
    public Conference(Videobridge videobridge,
                      String id,
                      @Nullable EntityBareJid conferenceName,
                      long gid,
                      @Nullable String meetingId,
                      boolean isRtcStatsEnabled,
                      boolean isCallStatsEnabled)
    {
        if (gid != GID_NOT_SET && gid != GID_COLIBRI2 &&  (gid < 0 || gid > 0xffff_ffffL))
        {
            throw new IllegalArgumentException("Invalid GID:" + gid);
        }
        this.meetingId = meetingId;
        this.videobridge = Objects.requireNonNull(videobridge, "videobridge");
        this.isRtcStatsEnabled = isRtcStatsEnabled;
        this.isCallStatsEnabled = isCallStatsEnabled;
        Map<String, String> context = JMap.ofEntries(
            entry("confId", id),
            entry("gid", String.valueOf(gid))
        );
        if (conferenceName != null)
        {
            context.put("conf_name", conferenceName.toString());
        }
        logger = new LoggerImpl(Conference.class.getName(), new LogContext(context));
        this.id = Objects.requireNonNull(id, "id");
        this.gid = gid;
        this.conferenceName = conferenceName;
        this.shim = new ConferenceShim(this, logger);
        this.colibri2Handler = new Colibri2ConferenceHandler(this, logger);
        colibriQueue = new PacketQueue<>(
                Integer.MAX_VALUE,
                true,
                "colibri-queue",
                request ->
                {
                    try
                    {
                        long start = System.currentTimeMillis();
                        IQ requestIQ = request.getRequest();
                        IQ response;
                        boolean expire = false;
                        if (requestIQ instanceof ColibriConferenceIQ)
                        {
                            response = shim.handleColibriConferenceIQ((ColibriConferenceIQ)requestIQ);
                        }
                        else if (requestIQ instanceof ConferenceModifyIQ)
                        {
                            Pair<IQ, Boolean> p =
                                colibri2Handler.handleConferenceModifyIQ((ConferenceModifyIQ)requestIQ);
                            response = p.getFirst();
                            expire = p.getSecond();
                        }
                        else
                        {
                            throw new IllegalStateException("Bad IQ " + request.getClass() + " passed to colibriIQ");
                        }
                        long end = System.currentTimeMillis();
                        long processingDelay = end - start;
                        long totalDelay = end - request.getReceiveTime();
                        request.getProcessingDelayStats().addDelay(processingDelay);
                        request.getTotalDelayStats().addDelay(totalDelay);
                        if (processingDelay > 100)
                        {
                            logger.warn("Took " + processingDelay + " ms to process an IQ (total delay "
                                    + totalDelay + " ms): " + request.getRequest().toXML());
                        }
                        request.getCallback().invoke(response);
                        if (expire) videobridge.expireConference(this);
                    }
                    catch (Throwable e)
                    {
                        logger.warn("Failed to handle colibri request: ", e);
                        request.getCallback().invoke(
                                IQUtils.createError(
                                        request.getRequest(),
                                        StanzaError.Condition.internal_server_error,
                                        e.getMessage()));
                    }
                    return true;
                },
                TaskPools.IO_POOL,
                Clock.systemUTC(), // TODO: using the Videobridge clock breaks tests somehow
                /* Allow running tasks to complete (so we can close the queue from within the task. */
                false
        );

        speechActivity = new ConferenceSpeechActivity(new SpeechActivityListener());
        updateLastNEndpointsFuture = TaskPools.SCHEDULED_POOL.scheduleAtFixedRate(() -> {
            try
            {
                if (speechActivity.updateLastNEndpoints())
                {
                    lastNEndpointsChangedAsync();
                }
            }
            catch (Exception e)
            {
                logger.warn("Failed to update lastN endpoints:", e);
            }

        }, 3, 3, TimeUnit.SECONDS);

        Videobridge.Statistics videobridgeStatistics = videobridge.getStatistics();
        videobridgeStatistics.totalConferencesCreated.incrementAndGet();
        epConnectionStatusMonitor = new EndpointConnectionStatusMonitor(this, TaskPools.SCHEDULED_POOL, logger);
        epConnectionStatusMonitor.start();
    }

    public void enqueueColibriRequest(XmppConnection.ColibriRequest request)
    {
        colibriQueue.add(request);
    }

    /**
     * Creates a new diagnostic context instance that includes the conference
     * name and the conference creation time.
     *
     * @return the new {@link DiagnosticContext} instance.
     */
    public DiagnosticContext newDiagnosticContext()
    {
        if (conferenceName != null)
        {
            DiagnosticContext diagnosticContext = new DiagnosticContext();
            diagnosticContext.put("conf_name", conferenceName.toString());
            diagnosticContext.put("conf_creation_time_ms", creationTime);
            return diagnosticContext;
        }
        else
        {
            return new NoOpDiagnosticContext();
        }
    }

    /**
     * Gets the statistics of this {@link Conference}.
     *
     * @return the statistics of this {@link Conference}.
     */
    public Statistics getStatistics()
    {
        return statistics;
    }

    /**
     * Sends a message to a subset of endpoints in the call, primary use
     * case being a message that has originated from an endpoint (as opposed to
     * a message originating from the bridge and being sent to all endpoints in
     * the call, for that see {@link #broadcastMessage(BridgeChannelMessage)}.
     *
     * @param msg the message to be sent
     * @param endpoints the list of <tt>Endpoint</tt>s to which the message will
     * be sent.
     */
    public void sendMessage(
        BridgeChannelMessage msg,
        List<Endpoint> endpoints,
        boolean sendToOcto)
    {
        for (Endpoint endpoint : endpoints)
        {
            endpoint.sendMessage(msg);
        }

        if (sendToOcto)
        {
            for (Relay relay: relaysById.values())
            {
                relay.sendMessage(msg);
            }
            if (tentacle != null)
            {
                tentacle.sendMessage(msg);
            }
        }
    }

    /**
     * Used to send a message to a subset of endpoints in the call, primary use
     * case being a message that has originated from an endpoint (as opposed to
     * a message originating from the bridge and being sent to all endpoints in
     * the call, for that see {@link #broadcastMessage(BridgeChannelMessage)}.
     *
     * @param msg the message to be sent
     * @param endpoints the list of <tt>Endpoint</tt>s to which the message will
     * be sent.
     */
    public void sendMessage(BridgeChannelMessage msg, List<Endpoint> endpoints)
    {
        sendMessage(msg, endpoints, false);
    }

    /**
     * Broadcasts a string message to all endpoints of the conference.
     *
     * @param msg the message to be broadcast.
     */
    public void broadcastMessage(BridgeChannelMessage msg, boolean sendToOcto)
    {
        sendMessage(msg, getLocalEndpoints(), sendToOcto);
    }

    /**
     * Broadcasts a string message to all endpoints of the conference.
     *
     * @param msg the message to be broadcast.
     */
    public void broadcastMessage(BridgeChannelMessage msg)
    {
        broadcastMessage(msg, false);
    }

    /**
     * Requests a keyframe from the endpoint with the specified id, if the
     * endpoint is found in the conference.
     *
     * @param endpointID the id of the endpoint to request a keyframe from.
     */
    public void requestKeyframe(String endpointID, long mediaSsrc)
    {
        AbstractEndpoint remoteEndpoint = getEndpoint(endpointID);

        if (remoteEndpoint != null)
        {
            remoteEndpoint.requestKeyframe(mediaSsrc);
        }
        else if (logger.isDebugEnabled())
        {
            logger.debug("Cannot request keyframe because the endpoint was not found.");
        }
    }

    /**
     * Handles a colibri2 {@link ConferenceModifyIQ} for this conference. Exposed for the cases when it needs to be
     * done synchronously (as opposed as by {@link #colibriQueue}).
     * @return the response as an IQ, either an {@link ErrorIQ} or a {@link ConferenceModifiedIQ}.
     */
    IQ handleConferenceModifyIQ(ConferenceModifyIQ conferenceModifyIQ)
    {
        Pair<IQ, Boolean> p = colibri2Handler.handleConferenceModifyIQ(conferenceModifyIQ);
        if (p.getSecond())
        {
            videobridge.expireConference(this);
        }
        return p.getFirst();
    }

    /**
     * Sets the values of the properties of a specific
     * <tt>ColibriConferenceIQ</tt> to the values of the respective
     * properties of this instance. Thus, the specified <tt>iq</tt> may be
     * thought of as a description of this instance.
     * <p>
     * <b>Note</b>: The copying of the values is shallow i.e. the
     * <tt>Content</tt>s of this instance are not described in the specified
     * <tt>iq</tt>.
     * </p>
     *
     * @param iq the <tt>ColibriConferenceIQ</tt> to set the values of the
     * properties of this instance on
     */
    public void describeShallow(ColibriConferenceIQ iq)
    {
        iq.setID(getID());
        if (conferenceName == null)
        {
            iq.setName(null);
        }
        else
        {
            iq.setName(conferenceName);
        }
    }

    /**
     * Runs {@link #lastNEndpointsChanged()} in an IO pool thread.
     */
    private void lastNEndpointsChangedAsync()
    {
        TaskPools.IO_POOL.execute(() ->
        {
            try
            {
                lastNEndpointsChanged();
            }
            catch (Exception e)
            {
                logger.warn("Failed to handle change in last N endpoints: ", e);
            }
        });
    }

    /**
     * Updates all endpoints with a new list of ordered endpoints in the conference.
     */
    private void lastNEndpointsChanged()
    {
        endpointsCache.forEach(Endpoint::lastNEndpointsChanged);
    }

    /**
     * Notifies this instance that {@link #speechActivity} has identified a speaker switch event and there is now a new
     * dominant speaker.
     * @param recentSpeakers the list of recent speakers (including the dominant speaker at index 0).
     */
    private void recentSpeakersChanged(List<AbstractEndpoint> recentSpeakers, boolean dominantSpeakerChanged)
    {
        if (!recentSpeakers.isEmpty())
        {
            List<String> recentSpeakersIds
                    = recentSpeakers.stream().map(AbstractEndpoint::getId).collect(Collectors.toList());
            logger.info("Recent speakers changed: " + recentSpeakersIds + ", dominant speaker changed: "
                    + dominantSpeakerChanged);
            broadcastMessage(new DominantSpeakerMessage(recentSpeakersIds));

            if (dominantSpeakerChanged)
            {
                getVideobridge().getStatistics().totalDominantSpeakerChanges.increment();
                if (getEndpointCount() > 2)
                {
                    maybeSendKeyframeRequest(recentSpeakers.get(0));
                }
            }
        }
    }

    /**
     * Schedules sending a pre-emptive keyframe request (if necessary) when a new dominant speaker is elected.
     * @param dominantSpeaker the new dominant speaker.
     */
    private void maybeSendKeyframeRequest(AbstractEndpoint dominantSpeaker)
    {
        if (dominantSpeaker == null)
        {
            return;
        }

        boolean anyEndpointInStageView = false;
        for (Endpoint otherEndpoint : getLocalEndpoints())
        {
            if (otherEndpoint != dominantSpeaker && otherEndpoint.isInStageView())
            {
                anyEndpointInStageView = true;
                break;
            }
        }

        if (!anyEndpointInStageView)
        {
            // If all other endpoints are in tile view, there is no switch to anticipate. Don't trigger an unnecessary
            // keyframe.
            getVideobridge().getStatistics().preemptiveKeyframeRequestsSuppressed.incrementAndGet();
            return;
        }
        getVideobridge().getStatistics().preemptiveKeyframeRequestsSent.incrementAndGet();

        double senderRtt = getRtt(dominantSpeaker);
        double maxReceiveRtt = getMaxReceiverRtt(dominantSpeaker.getId());
        // We add an additional 10ms delay to reduce the risk of the keyframe arriving too early
        double keyframeDelay = maxReceiveRtt - senderRtt + 10;
        if (logger.isDebugEnabled())
        {
            logger.debug("Scheduling keyframe request from " + dominantSpeaker.getId() + " after a delay" +
                    " of " + keyframeDelay + "ms");
        }
        TaskPools.SCHEDULED_POOL.schedule(
                (Runnable)dominantSpeaker::requestKeyframe,
                (long)keyframeDelay,
                TimeUnit.MILLISECONDS
        );
    }

    private double getRtt(AbstractEndpoint endpoint)
    {
        if (endpoint instanceof Endpoint)
        {
            Endpoint localDominantSpeaker = (Endpoint)endpoint;
            return localDominantSpeaker.getRtt();
        }
        else
        {
            // Octo endpoint
            // TODO(brian): we don't currently have a way to get the RTT from this bridge
            // to a remote endpoint, so we hard-code a value here.  Discussed this with
            // Boris, and we talked about perhaps having OctoEndpoint periodically
            // send pings to the remote endpoint to calculate its RTT from the perspective
            // of this bridge.
            return 100;
        }
    }

    private double getMaxReceiverRtt(String excludedEndpointId)
    {
        return endpointsCache.stream()
                .filter(ep -> !ep.getId().equalsIgnoreCase(excludedEndpointId))
                .map(Endpoint::getRtt)
                .mapToDouble(Double::valueOf)
                .max()
                .orElse(0);
    }

    /**
     * Expires this <tt>Conference</tt>, its <tt>Content</tt>s and their
     * respective <tt>Channel</tt>s. Releases the resources acquired by this
     * instance throughout its life time and prepares it to be garbage
     * collected.
     *
     * NOTE: this should _only_ be called by the Conference "manager" (in this
     * case, Videobridge).  If you need to expire a Conference from elsewhere, use
     * {@link Videobridge#expireConference(Conference)}
     */
    void expire()
    {
        if (!expired.compareAndSet(false, true))
        {
            return;
        }

        logger.info("Expiring.");

        colibriQueue.close();

        epConnectionStatusMonitor.stop();

        if (updateLastNEndpointsFuture != null)
        {
            updateLastNEndpointsFuture.cancel(true);
            updateLastNEndpointsFuture = null;
        }

        logger.debug(() -> "Expiring endpoints.");
        getEndpoints().forEach(AbstractEndpoint::expire);
        speechActivity.expire();
        if (tentacle != null)
        {
            tentacle.expire();
            tentacle = null;
        }

        updateStatisticsOnExpire();
    }

    /**
     * Updates the statistics for this conference when it is about to expire.
     */
    private void updateStatisticsOnExpire()
    {
        long durationSeconds = Math.round((System.currentTimeMillis() - creationTime) / 1000d);

        Videobridge.Statistics videobridgeStatistics = getVideobridge().getStatistics();

        videobridgeStatistics.totalConferencesCompleted.incrementAndGet();
        videobridgeStatistics.totalConferenceSeconds.addAndGet(durationSeconds);

        videobridgeStatistics.totalBytesReceived.addAndGet(statistics.totalBytesReceived.get());
        videobridgeStatistics.totalBytesSent.addAndGet(statistics.totalBytesSent.get());
        videobridgeStatistics.totalPacketsReceived.addAndGet(statistics.totalPacketsReceived.get());
        videobridgeStatistics.totalPacketsSent.addAndGet(statistics.totalPacketsSent.get());

        videobridgeStatistics.totalRelayBytesReceived.addAndGet(statistics.totalRelayBytesReceived.get());
        videobridgeStatistics.totalRelayBytesSent.addAndGet(statistics.totalRelayBytesSent.get());
        videobridgeStatistics.totalRelayPacketsReceived.addAndGet(statistics.totalRelayPacketsReceived.get());
        videobridgeStatistics.totalRelayPacketsSent.addAndGet(statistics.totalRelayPacketsSent.get());

        boolean hasFailed = statistics.hasIceFailedEndpoint && !statistics.hasIceSucceededEndpoint;
        boolean hasPartiallyFailed = statistics.hasIceFailedEndpoint && statistics.hasIceSucceededEndpoint;

        videobridgeStatistics.dtlsFailedEndpoints.addAndGet(statistics.dtlsFailedEndpoints.get());

        if (hasPartiallyFailed)
        {
            videobridgeStatistics.totalPartiallyFailedConferences.incrementAndGet();
        }

        if (hasFailed)
        {
            videobridgeStatistics.totalFailedConferences.incrementAndGet();
        }

        if (logger.isInfoEnabled())
        {
            StringBuilder sb = new StringBuilder("expire_conf,");
            sb.append("duration=").append(durationSeconds)
                .append(",has_failed=").append(hasFailed)
                .append(",has_partially_failed=").append(hasPartiallyFailed);
            logger.info(sb.toString());
        }
    }

    /**
     * Finds an <tt>Endpoint</tt> of this <tt>Conference</tt> which sends an RTP
     * stream with a specific SSRC and with a specific <tt>MediaType</tt>.
     *
     * @param receiveSSRC the SSRC of an RTP stream received by this
     * <tt>Conference</tt> whose sending <tt>Endpoint</tt> is to be found
     * @return <tt>Endpoint</tt> of this <tt>Conference</tt> which sends an RTP
     * stream with the specified <tt>ssrc</tt> and with the specified
     * <tt>mediaType</tt>; otherwise, <tt>null</tt>
     */
    AbstractEndpoint findEndpointByReceiveSSRC(long receiveSSRC)
    {
        return getEndpoints().stream()
                .filter(ep -> ep.receivesSsrc(receiveSSRC))
                .findFirst()
                .orElse(null);
    }

    /**
     * Gets an <tt>Endpoint</tt> participating in this <tt>Conference</tt> which
     * has a specific identifier/ID.
     *
     * @param id the identifier/ID of the <tt>Endpoint</tt> which is to be
     * returned
     * @return an <tt>Endpoint</tt> participating in this <tt>Conference</tt>
     * which has the specified <tt>id</tt> or <tt>null</tt>
     */
    @Nullable
    public AbstractEndpoint getEndpoint(@NotNull String id)
    {
        return endpointsById.get(Objects.requireNonNull(id, "id must be non null"));
    }

    @Nullable
    public Relay getRelay(@NotNull String id)
    {
        return relaysById.get(id);
    }

    @Nullable
    public AbstractEndpoint findSourceOwner(@NotNull String sourceName)
    {
        // TODO does it need to also handle OctoEndpoints?
        for (Endpoint e : endpointsCache)
        {
            if (e.findMediaSourceDesc(sourceName) != null)
            {
                return e;
            }
        }

        return null;
    }

    /**
     * Initializes a new <tt>Endpoint</tt> instance with the specified
     * <tt>id</tt> and adds it to the list of <tt>Endpoint</tt>s participating
     * in this <tt>Conference</tt>.
     * @param id the identifier/ID of the <tt>Endpoint</tt> which will be
     * created
     * @param iceControlling {@code true} if the ICE agent of this endpoint's
     * transport will be initialized to serve as a controlling ICE agent;
     * otherwise, {@code false}
     * @return an <tt>Endpoint</tt> participating in this <tt>Conference</tt>
     */
    @NotNull
    public Endpoint createLocalEndpoint(String id, boolean iceControlling)
    {
        final AbstractEndpoint existingEndpoint = getEndpoint(id);
        if (existingEndpoint instanceof OctoEndpoint)
        {
            // It is possible that an Endpoint was migrated from another bridge
            // in the conference to this one, and the sources lists (which
            // implicitly signal the Octo endpoints in the conference) haven't
            // been updated yet. We'll force the Octo endpoint to expire and
            // we'll continue with the creation of a new local Endpoint for the
            // participant.
            existingEndpoint.expire();
        }
        else if (existingEndpoint != null)
        {
            throw new IllegalArgumentException("Local endpoint with ID = " + id + "already created");
        }

        final Endpoint endpoint = new Endpoint(id, this, logger, iceControlling);

        subscribeToEndpointEvents(endpoint);

        addEndpoints(Collections.singleton(endpoint));

        return endpoint;
    }

    @NotNull
    public Relay createRelay(String id, boolean iceControlling, boolean useUniquePort)
    {
        final Relay existingRelay = getRelay(id);
        if (existingRelay != null)
        {
            throw new IllegalArgumentException("Relay with ID = " + id + "already created");
        }

        final Relay relay = new Relay(id, this, logger, iceControlling, useUniquePort);

        relaysById.put(id, relay);

        return relay;
    }

    private void subscribeToEndpointEvents(AbstractEndpoint endpoint)
    {
        endpoint.addEventHandler(new AbstractEndpoint.EventHandler()
        {
            @Override
            public void iceSucceeded()
            {
                getStatistics().hasIceSucceededEndpoint = true;
                getVideobridge().getStatistics().totalIceSucceeded.incrementAndGet();
            }

            @Override
            public void iceFailed()
            {
                getStatistics().hasIceFailedEndpoint = true;
                getVideobridge().getStatistics().totalIceFailed.incrementAndGet();
            }

            @Override
            public void sourcesChanged()
            {
                endpointSourcesChanged(endpoint);
            }
        });
    }

    /**
     * An endpoint was added or removed.
     */
    private void endpointsChanged()
    {
        speechActivity.endpointsChanged(getEndpoints());
    }

    /**
     * The media sources of one of the endpoints in this conference
     * changed.
     *
     * @param endpoint the endpoint, or {@code null} if it was an Octo endpoint.
     */
    public void endpointSourcesChanged(AbstractEndpoint endpoint)
    {
        // Force an update to be propagated to each endpoint's bitrate controller.
        lastNEndpointsChanged();
    }

    /**
     * Updates {@link #endpointsCache} with the current contents of
     * {@link #endpointsById}.
     */
    private void updateEndpointsCache()
    {
        synchronized (endpointsCacheLock)
        {
            ArrayList<Endpoint> endpointsList = new ArrayList<>(endpointsById.size());
            endpointsById.values().forEach(e ->
            {
                if (e instanceof Endpoint)
                {
                    endpointsList.add((Endpoint) e);
                }
            });

            endpointsCache = Collections.unmodifiableList(endpointsList);
        }
    }

    /**
     * Returns the number of local AND remote endpoints in this {@link Conference}.
     *
     * @return the number of local AND remote endpoints in this {@link Conference}.
     */
    public int getEndpointCount()
    {
        return endpointsById.size();
    }

    /**
     * @return the number of relays.
     */
    public int getRelayCount()
    {
        return relaysById.size();
    }

    /**
     * Returns the number of local endpoints in this {@link Conference}.
     *
     * @return the number of local endpoints in this {@link Conference}.
     */
    public int getLocalEndpointCount()
    {
        return getLocalEndpoints().size();
    }

    /**
     * Gets the <tt>Endpoint</tt>s participating in/contributing to this
     * <tt>Conference</tt>.
     *
     * @return the <tt>Endpoint</tt>s participating in/contributing to this
     * <tt>Conference</tt>
     */
    public List<AbstractEndpoint> getEndpoints()
    {
        return new ArrayList<>(this.endpointsById.values());
    }

    List<AbstractEndpoint> getOrderedEndpoints()
    {
        return speechActivity.getOrderedEndpoints();
    }

    /**
     * Gets the list of endpoints which are local to this bridge (as opposed
     * to being on a remote bridge through Octo).
     */
    public List<Endpoint> getLocalEndpoints()
    {
        return endpointsCache;
    }

    /**
     * Gets the list of the relays this conference is using.
     */
    public List<Relay> getRelays() { return new ArrayList<>(this.relaysById.values()); }

    /**
     * Gets the (unique) identifier/ID of this instance.
     *
     * @return the (unique) identifier/ID of this instance
     */
    public final String getID()
    {
        return id;
    }

    public final boolean isCallStatsEnabled()
    {
        return isCallStatsEnabled;
    }

    /**
     * Gets the signaling-server-defined meetingId of this conference, if set.
     *
     * @return The signaling-server-defined meetingId of this conference, or null.
     */
    public final @Nullable String getMeetingId()
    {
        return meetingId;
    }

    /**
     * Gets an <tt>Endpoint</tt> participating in this <tt>Conference</tt> which
     * has a specific identifier/ID.
     *
     * @param id the identifier/ID of the <tt>Endpoint</tt> which is to be
     * returned
     * @return an <tt>Endpoint</tt> participating in this <tt>Conference</tt>
     * or {@code null} if endpoint with specified ID does not exist in a
     * conference
     */
    @Nullable
    public Endpoint getLocalEndpoint(String id)
    {
        AbstractEndpoint endpoint = getEndpoint(id);
        if (endpoint instanceof Endpoint)
        {
            return (Endpoint) endpoint;
        }

        return null;
    }

    /**
     * Gets the speech activity (representation) of the <tt>Endpoint</tt>s of
     * this <tt>Conference</tt>.
     *
     * @return the speech activity (representation) of the <tt>Endpoint</tt>s of
     * this <tt>Conference</tt>
     */
    public ConferenceSpeechActivity getSpeechActivity()
    {
        return speechActivity;
    }

    /**
     * Gets the <tt>Videobridge</tt> which has initialized this
     * <tt>Conference</tt>.
     *
     * @return the <tt>Videobridge</tt> which has initialized this
     * <tt>Conference</tt>
     */
    public final Videobridge getVideobridge()
    {
        return videobridge;
    }

    /**
     * Gets the indicator which determines whether this <tt>Conference</tt> has
     * expired.
     *
     * @return <tt>true</tt> if this <tt>Conference</tt> has expired; otherwise,
     * <tt>false</tt>
     */
    public boolean isExpired()
    {
        return expired.get();
    }

    /**
     * Notifies this conference that one of its endpoints has expired.
     *
     * @param endpoint the <tt>Endpoint</tt> which expired.
     */
    public void endpointExpired(AbstractEndpoint endpoint)
    {
        final AbstractEndpoint removedEndpoint;
        String id = endpoint.getId();
        removedEndpoint = endpointsById.remove(id);
        if (removedEndpoint != null)
        {
            updateEndpointsCache();
        }

        endpointsById.forEach((i, senderEndpoint) -> senderEndpoint.removeReceiver(id));

        if (tentacle != null)
        {
            tentacle.endpointExpired(id);
        }

        if (removedEndpoint != null)
        {
            endpointsChanged();
        }
    }

    /**
     * Notifies this conference that one of its relays has expired.
     *
     * @param relay the <tt>Relay</tt> which expired.
     */
    public void relayExpired(Relay relay)
    {
        String id = relay.getId();
        relaysById.remove(id);

        getLocalEndpoints().forEach(senderEndpoint -> senderEndpoint.removeReceiver(id));
    }

    /**
     * Adds a set of {@link AbstractEndpoint} instances to the list of endpoints in this conference.
     */
    public void addEndpoints(Set<AbstractEndpoint> endpoints)
    {
        endpoints.forEach(endpoint -> {
            if (endpoint.getConference() != this)
            {
                throw new IllegalArgumentException("Endpoint belong to other " +
                        "conference = " + endpoint.getConference());
            }
            AbstractEndpoint replacedEndpoint = endpointsById.put(endpoint.getId(), endpoint);
            if (replacedEndpoint != null)
            {
                logger.info("Endpoint with id " + endpoint.getId() + ": " +
                        replacedEndpoint + " has been replaced by new " +
                        "endpoint with same id: " + endpoint);
            }
        });

        updateEndpointsCache();

        endpointsChanged();
    }

    /**
     * Notifies this {@link Conference} that one of its endpoints'
     * transport channel has become available.
     *
     * @param abstractEndpoint the endpoint whose transport channel has become
     * available.
     */
    @Override
    public void endpointMessageTransportConnected(@NotNull AbstractEndpoint abstractEndpoint)
    {
        /* TODO: this is only called for actual Endpoints, but the API needs cleaning up. */
        Endpoint endpoint = (Endpoint)abstractEndpoint;

        epConnectionStatusMonitor.endpointConnected(endpoint.getId());

        if (!isExpired())
        {
            List<String> recentSpeakers = speechActivity.getRecentSpeakers();

            if (!recentSpeakers.isEmpty())
            {
                endpoint.sendMessage(new DominantSpeakerMessage(recentSpeakers));
            }
        }
    }

    /**
     * Gets the conference name.
     *
     * @return the conference name
     */
    public @Nullable EntityBareJid getName()
    {
        return conferenceName;
    }

    /**
     * @return the {@link Logger} used by this instance.
     */
    public Logger getLogger()
    {
        return logger;
    }

    /**
     * @return the global ID of the conference (see {@link #gid)}, or
     * {@link #GID_NOT_SET} if none has been set.
     * @see #gid
     */
    public long getGid()
    {
        return gid;
    }

    /**
     * @return {@code true} if this {@link Conference} is ready to be expired.
     */
    public boolean shouldExpire()
    {
        // Allow a conference to have no endpoints in the first 20 seconds.
        return getEndpointCount() == 0
                && (System.currentTimeMillis() - creationTime > 20000);
    }

    /**
     * @return this {@link Conference}'s Colibri shim.
     */
    public ConferenceShim getShim()
    {
        return shim;
    }

    /**
     * Determine whether to forward an audio packet.
     * @param sourceEndpointId the source endpoint.
     * @return whether to forward the packet.
     */
    private boolean shouldSendAudio(String sourceEndpointId)
    {
        DominantSpeakerIdentification<String>.SpeakerRanking ranking = speechActivity.getRanking(sourceEndpointId);
        if (ranking.isDominant && LoudestConfig.Companion.getAlwaysRouteDominant())
            return true;
        if (ranking.energyRanking < LoudestConfig.Companion.getNumLoudest())
            return true;
        videobridge.getStatistics().tossedPacketsEnergy.addValue(ranking.energyScore);
        return false;
    }

    /**
     * Determine whether a given endpointId is currently a ranked speaker, if
     * speaker ranking is currently enabled.
     */
    public boolean isRankedSpeaker(AbstractEndpoint ep)
    {
        if (!LoudestConfig.Companion.getRouteLoudestOnly())
        {
            return false;
        }
        DominantSpeakerIdentification<String>.SpeakerRanking ranking = speechActivity.getRanking(ep.getId());
        return ranking.energyRanking < LoudestConfig.Companion.getNumLoudest();
    }

    /**
     * Broadcasts the packet to all endpoints and tentacles that want it.
     *
     * @param packetInfo the packet
     */
    private void sendOut(PacketInfo packetInfo)
    {
        String sourceEndpointId = packetInfo.getEndpointId();
        // We want to avoid calling 'clone' for the last receiver of this packet
        // since it's unnecessary.  To do so, we'll wait before we clone and send
        // to an interested handler until after we've determined another handler
        // is also interested in the packet.  We'll give the last handler the
        // original packet (without cloning).
        PotentialPacketHandler prevHandler = null;

        boolean discard = LoudestConfig.Companion.getRouteLoudestOnly()
            && packetInfo.getPacket() instanceof AudioRtpPacket
            && !shouldSendAudio(sourceEndpointId);
        if (!discard)
        {
            for (Endpoint endpoint : endpointsCache)
            {
                if (endpoint.getId().equals(sourceEndpointId))
                {
                    continue;
                }

                if (endpoint.wants(packetInfo))
                {
                    if (prevHandler != null)
                    {
                        prevHandler.send(packetInfo.clone());
                    }
                    prevHandler = endpoint;
                }
            }
            for (Relay relay: relaysById.values())
            {
                if (relay.wants(packetInfo))
                {
                    if (prevHandler != null)
                    {
                        prevHandler.send(packetInfo.clone());
                    }
                    prevHandler = relay;
                }
            }
            if (tentacle != null && tentacle.wants(packetInfo))
            {
                if (prevHandler != null)
                {
                    prevHandler.send(packetInfo.clone());
                }
                prevHandler = tentacle;
            }
        }

        if (prevHandler != null)
        {
            prevHandler.send(packetInfo);
        }
        else
        {
            // No one wanted the packet, so the buffer is now free!
            ByteBufferPool.returnBuffer(packetInfo.getPacket().getBuffer());
        }
    }

    /**
     * @return The {@link ConfOctoTransport} for this conference.
     */
    public ConfOctoTransport getTentacle()
    {
        if (gid == GID_NOT_SET)
        {
            throw new IllegalStateException("Can not enable Octo without the GID being set.");
        }
        if (tentacle == null)
        {
            tentacle = new ConfOctoTransport(this);
        }
        return tentacle;
    }

    public boolean isOctoEnabled()
    {
        return tentacle != null;
    }

    /**
     * Handles an RTP/RTCP packet coming from a specific endpoint.
     * @param packetInfo
     */
    public void handleIncomingPacket(PacketInfo packetInfo)
    {
        Packet packet = packetInfo.getPacket();
        if (packet instanceof RtpPacket)
        {
            // This is identical to the default 'else' below, but it defined
            // because the vast majority of packet will follow this path.
            sendOut(packetInfo);
        }
        else if (packet instanceof RtcpFbPliPacket || packet instanceof RtcpFbFirPacket)
        {
            long mediaSsrc = (packet instanceof RtcpFbPliPacket)
                ? ((RtcpFbPliPacket) packet).getMediaSourceSsrc()
                : ((RtcpFbFirPacket) packet).getMediaSenderSsrc();

            // XXX we could make this faster with a map
            AbstractEndpoint targetEndpoint = findEndpointByReceiveSSRC(mediaSsrc);

            PotentialPacketHandler pph = null;
            if (targetEndpoint instanceof Endpoint)
            {
                pph = (Endpoint) targetEndpoint;
            }
            else if (targetEndpoint instanceof RelayedEndpoint)
            {
                pph = ((RelayedEndpoint)targetEndpoint).getRelay();
            }
            else if (targetEndpoint instanceof OctoEndpoint)
            {
                pph = tentacle;
            }

            // This is not a redundant check. With Octo and 3 or more bridges,
            // some PLI or FIR will come from Octo but the target endpoint will
            // also be Octo. We need to filter these out.
            if (pph == null)
            {
                if (logger.isDebugEnabled())
                {
                    logger.debug("Dropping FIR/PLI for media ssrc " + mediaSsrc);
                }
            }
            else if (pph.wants(packetInfo))
            {
                pph.send(packetInfo);
            }
        }
        else
        {
            sendOut(packetInfo);
        }
    }

    /**
     * Gets a JSON representation of the parts of this object's state that
     * are deemed useful for debugging.
     */
    public JSONObject getDebugState()
    {
        return getDebugState(true);
    }

    /**
     * Gets a JSON representation of the parts of this object's state that
     * are deemed useful for debugging.
     *
     * @param full if specified the result will include more details and will
     * include the debug state of the endpoint(s). Otherwise just the IDs and
     * names of the conference and endpoints are included.
     */
    public JSONObject getDebugState(boolean full)
    {
        return getDebugState(full, null);
    }

    /**
     * Gets a JSON representation of the parts of this object's state that
     * are deemed useful for debugging.
     *
     * @param full if specified the result will include more details and will
     * include the debug state of the endpoint(s). Otherwise just the IDs and
     * names of the conference and endpoints are included.
     * @param endpointId the ID of the endpoint to include. If set to
     * {@code null}, all endpoints will be included.
     */
    @SuppressWarnings("unchecked")
    public JSONObject getDebugState(boolean full, String endpointId)
    {
        JSONObject debugState = new JSONObject();
        debugState.put("id", id);
        debugState.put("rtcstatsEnabled", isRtcStatsEnabled);

        if (conferenceName != null)
        {
            debugState.put("name", conferenceName.toString());
        }
        if (meetingId != null)
        {
            debugState.put("meeting_id", meetingId);
        }

        if (full)
        {
            debugState.put("gid", gid);
            debugState.put("expired", expired.get());
            debugState.put("creationTime", creationTime);
            debugState.put("speechActivity", speechActivity.getDebugState());
            debugState.put("statistics", statistics.getJson());
            //debugState.put("encodingsManager", encodingsManager.getDebugState());
            ConfOctoTransport tentacle = this.tentacle;
            debugState.put(
                    "tentacle",
                    tentacle == null ? null : tentacle.getDebugState());
        }

        JSONObject endpoints = new JSONObject();
        debugState.put("endpoints", endpoints);
        for (Endpoint e : endpointsCache)
        {
            if (endpointId == null || endpointId.equals(e.getId()))
            {
                endpoints.put(e.getId(), full ? e.getDebugState() : e.getStatsId());
            }
        }

        JSONObject relays = new JSONObject();
        debugState.put("relays", relays);
        for (Relay r : relaysById.values())
        {
            relays.put(r.getId(), full ? r.getDebugState() : r.getId());
        }

        return debugState;
    }

    /**
     * Whether this looks like a conference in which the two endpoints are
     * using a peer-to-peer connection (i.e. none of them are sending audio
     * or video).
     * This has false positives when e.g. an endpoint doesn't support p2p
     * (firefox) and both are audio/video muted.
     */
    public boolean isP2p()
    {
        return isInactive() && getEndpointCount() == 2;
    }

    /**
     * Whether the conference is inactive, in the sense that none of its
     * local endpoints or relays are sending audio or video.
     */
    public boolean isInactive()
    {
        /* N.B.: this could be changed to iterate over getLocalEndpoints() once old Octo is removed,
         * and these methods removed from AbstractEndpoint, assuming we don't go to transceiver-per-endpoint
         * for Secure Octo.
         */
        return getEndpoints().stream().noneMatch(e -> e.isSendingAudio() || e.isSendingVideo()) &&
            getRelays().stream().noneMatch(r -> r.isSendingAudio() || r.isSendingVideo());
    }

    @NotNull public EncodingsManager getEncodingsManager()
    {
        return encodingsManager;
    }

    /**
     * Holds conference statistics.
     */
    public static class Statistics
    {
        /**
         * The total number of bytes received in RTP packets in channels in this
         * conference. Note that this is only updated when channels expire.
         */
        public AtomicLong totalBytesReceived = new AtomicLong();

        /**
         * The total number of bytes sent in RTP packets in channels in this
         * conference. Note that this is only updated when channels expire.
         */
        public AtomicLong totalBytesSent = new AtomicLong();

        /**
         * The total number of RTP packets received in channels in this
         * conference. Note that this is only updated when channels expire.
         */
        public AtomicLong totalPacketsReceived = new AtomicLong();

        /**
         * The total number of RTP packets received in channels in this
         * conference. Note that this is only updated when channels expire.
         */
        public AtomicLong totalPacketsSent = new AtomicLong();

        /**
         * The total number of bytes received in RTP packets in relays in this
         * conference. Note that this is only updated when relays expire.
         */
        public AtomicLong totalRelayBytesReceived = new AtomicLong();

        /**
         * The total number of bytes sent in RTP packets in relays in this
         * conference. Note that this is only updated when relays expire.
         */
        public AtomicLong totalRelayBytesSent = new AtomicLong();

        /**
         * The total number of RTP packets received in relays in this
         * conference. Note that this is only updated when relays expire.
         */
        public AtomicLong totalRelayPacketsReceived = new AtomicLong();

        /**
         * The total number of RTP packets received in relays in this
         * conference. Note that this is only updated when relays expire.
         */
        public AtomicLong totalRelayPacketsSent = new AtomicLong();

        /**
         * Whether at least one endpoint in this conference failed ICE.
         */
        public boolean hasIceFailedEndpoint = false;

        /**
         * Whether at least one endpoint in this conference completed ICE
         * successfully.
         */
        public boolean hasIceSucceededEndpoint = false;

        /**
         * Number of endpoints whose ICE connection was established, but DTLS
         * wasn't (at the time of expiration).
         */
        public AtomicInteger dtlsFailedEndpoints = new AtomicInteger();

        /**
         * Gets a snapshot of this object's state as JSON.
         */
        @SuppressWarnings("unchecked")
        private JSONObject getJson()
        {
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("total_bytes_received", totalBytesReceived.get());
            jsonObject.put("total_bytes_sent", totalBytesSent.get());
            jsonObject.put("total_packets_received", totalPacketsReceived.get());
            jsonObject.put("total_packets_sent", totalPacketsSent.get());
            jsonObject.put("total_relay_bytes_received", totalRelayBytesReceived.get());
            jsonObject.put("total_relay_bytes_sent", totalRelayBytesSent.get());
            jsonObject.put("total_relay_packets_received", totalRelayPacketsReceived.get());
            jsonObject.put("total_relay_packets_sent", totalRelayPacketsSent.get());
            jsonObject.put("has_failed_endpoint", hasIceFailedEndpoint);
            jsonObject.put("has_succeeded_endpoint", hasIceSucceededEndpoint);
            jsonObject.put("dtls_failed_endpoints", dtlsFailedEndpoints.get());
            return jsonObject;
        }
    }

    /**
     * This is a no-op diagnostic context (one that will record nothing) meant
     * to disable logging of time-series for health checks.
     */
    static class NoOpDiagnosticContext
        extends DiagnosticContext
    {
        @Override
        public TimeSeriesPoint makeTimeSeriesPoint(String timeSeriesName, long tsMs)
        {
            return new NoOpTimeSeriesPoint();
        }

        @Override
        public Object put(@NotNull String key, @NotNull Object value)
        {
            return null;
        }
    }

    static class NoOpTimeSeriesPoint
        extends DiagnosticContext.TimeSeriesPoint
    {
        public NoOpTimeSeriesPoint()
        {
            this(Collections.emptyMap());
        }

        public NoOpTimeSeriesPoint(Map<String, Object> m)
        {
            super(m);
        }

        @Override
        public Object put(String key, Object value)
        {
            return null;
        }
    }

    private class SpeechActivityListener implements ConferenceSpeechActivity.Listener
    {
        @Override
        public void recentSpeakersChanged(List<AbstractEndpoint> recentSpeakers, boolean dominantSpeakerChanged)
        {
            Conference.this.recentSpeakersChanged(recentSpeakers, dominantSpeakerChanged);
        }

        @Override
        public void lastNEndpointsChanged()
        {
            Conference.this.lastNEndpointsChanged();
        }
    }
}
