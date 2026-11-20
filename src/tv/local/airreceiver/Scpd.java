package tv.local.airreceiver;

/** UPnP service descriptions (SCPD) for the three MediaRenderer services. */
final class Scpd {
    /*
     * Compact spec: actions are "Name:in Arg Var,out Arg Var,...", state variables are
     * "Name[:type[:events[:allowed|values]]]" (type defaults to string, events to "no").
     */
    static final String AVTRANSPORT = build(new String[] {
            "SetAVTransportURI:in InstanceID A_ARG_TYPE_InstanceID,in CurrentURI AVTransportURI,in CurrentURIMetaData AVTransportURIMetaData",
            "SetNextAVTransportURI:in InstanceID A_ARG_TYPE_InstanceID,in NextURI NextAVTransportURI,in NextURIMetaData NextAVTransportURIMetaData",
            "GetMediaInfo:in InstanceID A_ARG_TYPE_InstanceID,out NrTracks NumberOfTracks,out MediaDuration CurrentMediaDuration,"
                    + "out CurrentURI AVTransportURI,out CurrentURIMetaData AVTransportURIMetaData,out NextURI NextAVTransportURI,"
                    + "out NextURIMetaData NextAVTransportURIMetaData,out PlayMedium PlaybackStorageMedium,"
                    + "out RecordMedium RecordStorageMedium,out WriteStatus RecordMediumWriteStatus",
            "GetTransportInfo:in InstanceID A_ARG_TYPE_InstanceID,out CurrentTransportState TransportState,"
                    + "out CurrentTransportStatus TransportStatus,out CurrentSpeed TransportPlaySpeed",
            "GetPositionInfo:in InstanceID A_ARG_TYPE_InstanceID,out Track CurrentTrack,out TrackDuration CurrentTrackDuration,"
                    + "out TrackMetaData CurrentTrackMetaData,out TrackURI CurrentTrackURI,out RelTime RelativeTimePosition,"
                    + "out AbsTime AbsoluteTimePosition,out RelCount RelativeCounterPosition,out AbsCount AbsoluteCounterPosition",
            "GetDeviceCapabilities:in InstanceID A_ARG_TYPE_InstanceID,out PlayMedia PossiblePlaybackStorageMedia,"
                    + "out RecMedia PossibleRecordStorageMedia,out RecQualityModes PossibleRecordQualityModes",
            "GetTransportSettings:in InstanceID A_ARG_TYPE_InstanceID,out PlayMode CurrentPlayMode,out RecQualityMode CurrentRecordQualityMode",
            "GetCurrentTransportActions:in InstanceID A_ARG_TYPE_InstanceID,out Actions CurrentTransportActions",
            "Stop:in InstanceID A_ARG_TYPE_InstanceID",
            "Play:in InstanceID A_ARG_TYPE_InstanceID,in Speed TransportPlaySpeed",
            "Pause:in InstanceID A_ARG_TYPE_InstanceID",
            "Seek:in InstanceID A_ARG_TYPE_InstanceID,in Unit A_ARG_TYPE_SeekMode,in Target A_ARG_TYPE_SeekTarget",
            "Next:in InstanceID A_ARG_TYPE_InstanceID",
            "Previous:in InstanceID A_ARG_TYPE_InstanceID",
    }, new String[] {
            "LastChange:string:yes",
            "TransportState:string:no:STOPPED|PLAYING|TRANSITIONING|PAUSED_PLAYBACK|NO_MEDIA_PRESENT",
            "TransportStatus:string:no:OK|ERROR_OCCURRED",
            "PlaybackStorageMedium", "RecordStorageMedium", "PossiblePlaybackStorageMedia", "PossibleRecordStorageMedia",
            "CurrentPlayMode:string:no:NORMAL", "TransportPlaySpeed:string:no:1", "RecordMediumWriteStatus",
            "CurrentRecordQualityMode", "PossibleRecordQualityModes", "NumberOfTracks:ui4", "CurrentTrack:ui4",
            "CurrentTrackDuration", "CurrentMediaDuration", "CurrentTrackMetaData", "CurrentTrackURI", "AVTransportURI",
            "AVTransportURIMetaData", "NextAVTransportURI", "NextAVTransportURIMetaData", "RelativeTimePosition",
            "AbsoluteTimePosition", "RelativeCounterPosition:i4", "AbsoluteCounterPosition:i4", "CurrentTransportActions",
            "A_ARG_TYPE_SeekMode:string:no:REL_TIME|ABS_TIME|TRACK_NR", "A_ARG_TYPE_SeekTarget", "A_ARG_TYPE_InstanceID:ui4",
    });

    static final String RENDERING_CONTROL = build(new String[] {
            "ListPresets:in InstanceID A_ARG_TYPE_InstanceID,out CurrentPresetNameList PresetNameList",
            "SelectPreset:in InstanceID A_ARG_TYPE_InstanceID,in PresetName A_ARG_TYPE_PresetName",
            "GetMute:in InstanceID A_ARG_TYPE_InstanceID,in Channel A_ARG_TYPE_Channel,out CurrentMute Mute",
            "SetMute:in InstanceID A_ARG_TYPE_InstanceID,in Channel A_ARG_TYPE_Channel,in DesiredMute Mute",
            "GetVolume:in InstanceID A_ARG_TYPE_InstanceID,in Channel A_ARG_TYPE_Channel,out CurrentVolume Volume",
            "SetVolume:in InstanceID A_ARG_TYPE_InstanceID,in Channel A_ARG_TYPE_Channel,in DesiredVolume Volume",
    }, new String[] {
            "LastChange:string:yes", "PresetNameList", "Mute:boolean", "Volume:ui2",
            "A_ARG_TYPE_Channel:string:no:Master", "A_ARG_TYPE_InstanceID:ui4", "A_ARG_TYPE_PresetName:string:no:FactoryDefaults",
    });

    static final String CONNECTION_MANAGER = build(new String[] {
            "GetProtocolInfo:out Source SourceProtocolInfo,out Sink SinkProtocolInfo",
            "GetCurrentConnectionIDs:out ConnectionIDs CurrentConnectionIDs",
            "GetCurrentConnectionInfo:in ConnectionID A_ARG_TYPE_ConnectionID,out RcsID A_ARG_TYPE_RcsID,"
                    + "out AVTransportID A_ARG_TYPE_AVTransportID,out ProtocolInfo A_ARG_TYPE_ProtocolInfo,"
                    + "out PeerConnectionManager A_ARG_TYPE_ConnectionManager,out PeerConnectionID A_ARG_TYPE_ConnectionID,"
                    + "out Direction A_ARG_TYPE_Direction,out Status A_ARG_TYPE_ConnectionStatus",
    }, new String[] {
            "SourceProtocolInfo:string:yes", "SinkProtocolInfo:string:yes", "CurrentConnectionIDs:string:yes",
            "A_ARG_TYPE_ConnectionStatus:string:no:OK|ContentFormatMismatch|InsufficientBandwidth|UnreliableChannel|Unknown",
            "A_ARG_TYPE_ConnectionManager", "A_ARG_TYPE_Direction:string:no:Input|Output", "A_ARG_TYPE_ProtocolInfo",
            "A_ARG_TYPE_ConnectionID:i4", "A_ARG_TYPE_AVTransportID:i4", "A_ARG_TYPE_RcsID:i4",
    });

    private static String build(String[] actions, String[] vars) {
        StringBuilder b = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\"><specVersion><major>1</major><minor>0</minor></specVersion><actionList>");
        for (String a : actions) {
            int colon = a.indexOf(':');
            String name = colon < 0 ? a : a.substring(0, colon);
            b.append("<action><name>").append(name).append("</name><argumentList>");
            if (colon >= 0) {
                for (String arg : a.substring(colon + 1).split(",")) {
                    String[] p = arg.trim().split(" ");
                    b.append("<argument><name>").append(p[1]).append("</name><direction>").append(p[0])
                     .append("</direction><relatedStateVariable>").append(p[2]).append("</relatedStateVariable></argument>");
                }
            }
            b.append("</argumentList></action>");
        }
        b.append("</actionList><serviceStateTable>");
        for (String v : vars) {
            String[] p = v.split(":");
            String type = p.length > 1 ? p[1] : "string";
            String events = p.length > 2 ? p[2] : "no";
            b.append("<stateVariable sendEvents=\"").append(events).append("\"><name>").append(p[0])
             .append("</name><dataType>").append(type).append("</dataType>");
            if (p.length > 3) {
                b.append("<allowedValueList>");
                for (String val : p[3].split("\\|")) b.append("<allowedValue>").append(val).append("</allowedValue>");
                b.append("</allowedValueList>");
            }
            if (p[0].equals("Volume")) {
                b.append("<allowedValueRange><minimum>0</minimum><maximum>100</maximum><step>1</step></allowedValueRange>");
            }
            b.append("</stateVariable>");
        }
        b.append("</serviceStateTable></scpd>");
        return b.toString();
    }

    private Scpd() {}
}
