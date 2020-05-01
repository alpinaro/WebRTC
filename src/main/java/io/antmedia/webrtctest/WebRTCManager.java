package io.antmedia.webrtctest;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.CapturerObserver;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.Logging;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnection.IceConnectionState;
import org.webrtc.PeerConnection.IceGatheringState;
import org.webrtc.PeerConnection.IceServer;
import org.webrtc.PeerConnection.Observer;
import org.webrtc.PeerConnection.SignalingState;
import org.webrtc.PeerConnectionFactory.Options;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SessionDescription.Type;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;
import org.webrtc.DataChannel.Buffer;
import org.webrtc.DataChannel.State;
import org.webrtc.audio.JavaAudioDeviceModule;
import org.webrtc.audio.WebRtcAudioRecord;
import org.webrtc.audio.WebRtcAudioTrack;

import io.antmedia.enterprise.webrtc.codec.VirtualVideoDecoder;
import io.antmedia.enterprise.webrtc.codec.VirtualVideoDecoderFactory;
import io.antmedia.enterprise.webrtc.codec.VirtualVideoEncoder;
import io.antmedia.enterprise.webrtc.codec.VirtualVideoEncoderFactory;
import io.antmedia.webrtc.api.IAudioRecordListener;
import io.antmedia.webrtc.api.IAudioTrackListener;


public class WebRTCManager implements Observer, SdpObserver {
	private Logger logger = LoggerFactory.getLogger(WebRTCManager.class);
	
	String stunServerUri = "stun:stun.l.google.com:19302";
	private PeerConnectionFactory peerConnectionFactory;
	private PeerConnection peerConnection;

	private String streamId;
	private WebRtcAudioTrack webRtcAudioTrack;
	private WebsocketClientEndpoint websocket;
	private MediaConstraints audioConstraints;
	private MediaConstraints sdpMediaConstraints;
	private StreamManager streamManager;

	private VirtualVideoEncoderFactory encoderFactory;
	private CapturerObserver capturerObserver;
	private WebRtcAudioRecord audioRecord;
	private VirtualVideoDecoderFactory decoderFactory;
	private IWebRTCEventListerner listener;
	private volatile boolean isStopped  = false;
	private boolean descriptionReady = false;
	Queue<IceCandidate> iceCandidateQueue = new ConcurrentLinkedQueue<>();
	private boolean connected = false;

	private static final String AUDIO_ECHO_CANCELLATION_CONSTRAINT = "googEchoCancellation";
	private static final String AUDIO_AUTO_GAIN_CONTROL_CONSTRAINT = "googAutoGainControl";
	private static final String AUDIO_HIGH_PASS_FILTER_CONSTRAINT = "googHighpassFilter";
	private static final String AUDIO_NOISE_SUPPRESSION_CONSTRAINT = "googNoiseSuppression";
	private static final String FALSE = "false";
	public static final String VIDEO_TRACK_ID = "ARDAMSv0";

	public static final String AUDIO_TRACK_ID = "ARDAMSa0";

	private ScheduledExecutorService signallingExecutor = Executors.newSingleThreadScheduledExecutor();
	private Settings settings;

	private DataChannel dataChannel;

	public WebRTCManager(String streamId, Settings settings) 
	{
		this.settings = settings;
		this.setStreamId(streamId);

		websocket = new WebsocketClientEndpoint(settings);
		websocket.setManager(this);
	}
	
	public void webSocketOpened() {
		signallingExecutor.scheduleWithFixedDelay(() -> {
			websocket.sendPingMessage();
			
		}, 5, 5, TimeUnit.SECONDS);
	}

	private void initPeerConnection() {

		signallingExecutor.execute(() -> {
			createMediaConstraintsInternal();
			peerConnectionFactory = createPeerConnectionFactory();

			List<IceServer> iceServers = new ArrayList<>();
			iceServers.add(IceServer.builder(stunServerUri).createIceServer());
			PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);

			rtcConfig.enableDtlsSrtp = true;
			rtcConfig.disableIpv6 = true;
			//rtcConfig.tcpCandidatePolicy = TcpCandidatePolicy.ENABLED; 

			logger.info("Creating peerconnection hascode:{} time:{}" , WebRTCManager.this.hashCode(), System.currentTimeMillis());
			peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, WebRTCManager.this);



			if (streamManager instanceof WebRTCPublisher) {
				//why ARDAMS is used
				List<String> mediaStreamLabels = Collections.singletonList("ARDAMS");

				//instantiate video source
				VideoSource videoSource = peerConnectionFactory.createVideoSource(false);
				capturerObserver = videoSource.getCapturerObserver();

				//by normal app, video capturer started immediately after creating creating peer connection


				/**
				 *  It seems that we do not need custom video capturer technically
				 *  because video capturer call capturerObserver's below functions.
				 *  We can call below functions in WebRTCClient. 
				 *  
				 *  capturerObserver.onCapturerStarted(success)
				 *  capturerObserver.onCapturerStopped();
				 *  capturerObserver.onFrameCaptured(frame);
				 */

				VideoTrack videoTrack = peerConnectionFactory.createVideoTrack(VIDEO_TRACK_ID, videoSource);

				peerConnection.addTrack(videoTrack, mediaStreamLabels);

				AudioSource audioSource = peerConnectionFactory.createAudioSource(audioConstraints);
				AudioTrack localAudioTrack = peerConnectionFactory.createAudioTrack(AUDIO_TRACK_ID, audioSource);

				peerConnection.addTrack(localAudioTrack, mediaStreamLabels);

				capturerObserver = videoSource.getCapturerObserver();
				
				if(settings.dataChannel) {
					this.dataChannel = peerConnection.createDataChannel(streamId, new DataChannel.Init());

					dataChannel.registerObserver(new DataChannel.Observer() {
						@Override
						public void onStateChange() {
							logger.info("DataChannel State Change for stream Id {} state:{}", streamId, dataChannel.state());
							if(dataChannel.state() == State.CLOSED) {
								dataChannel.dispose();
								WebRTCManager.this.dataChannel = null;
							}
						}

						@Override
						public void onMessage(Buffer buffer) {
							logger.debug("DataChannel message received stream Id {}", streamId);
							byte[] data = new byte[buffer.data.capacity()];
							buffer.data.get(data);
							listener.onDataChannelMessage(new String(data));
						}

						@Override
						public void onBufferedAmountChange(long previousAmount) {
							logger.debug("DataChannel Buffered Amount Change Id {}", streamId);
						}
					});
				}
			}

			Logging.enableLogToDebugOutput(settings.logLevel);

		});
	}

	public void setRemoteDescription(SessionDescription sdp) {
		//System.out.println("******************\nsetRemoteDescription\n"+sdp.description+"\n**************************");
		signallingExecutor.execute(() -> {
			if (peerConnection != null) {
				peerConnection.setRemoteDescription(WebRTCManager.this, sdp);
			}
			else {
				logger.warn("Peer connection is null. It cannot add ice candidate for stream Id {}", getStreamId());
			}
		});
	}


	private PeerConnectionFactory createPeerConnectionFactory(){
		PeerConnectionFactory.initialize(
				PeerConnectionFactory.InitializationOptions.builder()
				.setFieldTrials(null)
				.createInitializationOptions());

		
		encoderFactory = new VirtualVideoEncoderFactory(settings.codec == VideoCodec.H264, settings.codec == VideoCodec.VP8);

		decoderFactory = new VirtualVideoDecoderFactory(settings.codec == VideoCodec.H264, settings.codec == VideoCodec.VP8); 

		PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
		options.disableNetworkMonitor = true;
		options.networkIgnoreMask = Options.ADAPTER_TYPE_LOOPBACK;

		JavaAudioDeviceModule adm;

		if(settings.mode == Mode.PUBLISHER) {
			adm = (JavaAudioDeviceModule)JavaAudioDeviceModule.builder(null)
					.setUseHardwareAcousticEchoCanceler(false)
					.setUseHardwareNoiseSuppressor(false)
					.setAudioRecordErrorCallback(null)
					.setAudioTrackErrorCallback(null)
					.setAudioRecordListener(new IAudioRecordListener() {

						@Override
						public void audioRecordStoppped() {
						}

						@Override
						public void audioRecordStarted() {
						}
					})
					.createAudioDeviceModule();

			audioRecord = adm.getAudioRecord();
		}
		else {
			// in receiving stream only Audio Track should be enabled
			// in sending stream only AudioRecord should be enabled 
			adm = (JavaAudioDeviceModule)
					JavaAudioDeviceModule.builder(null)
					.setUseHardwareAcousticEchoCanceler(false)
					.setUseHardwareNoiseSuppressor(false)
					.setAudioRecordErrorCallback(null)
					.setAudioTrackErrorCallback(null)
					.setAudioTrackListener(new IAudioTrackListener() {
						public void playoutStarted() {
							logger.info("starting playout for stream {}", streamId);
						}

						public void playoutStopped() {
							logger.info("stopping playout for stream {}", streamId);

						}
					})
					.createAudioDeviceModule();

			webRtcAudioTrack = adm.getAudioTrack();
		}

		return  PeerConnectionFactory.builder()
				.setOptions(options)
				.setAudioDeviceModule(adm)
				.setVideoEncoderFactory(encoderFactory)
				.setVideoDecoderFactory(decoderFactory)
				.createPeerConnectionFactory();
	}

	public void createOffer() {
		
		signallingExecutor.execute(() -> {
			peerConnection.createOffer(WebRTCManager.this, sdpMediaConstraints);
		});
		
		
	}

	private void createMediaConstraintsInternal() {

		audioConstraints = new MediaConstraints();
		audioConstraints.mandatory.add(
				new MediaConstraints.KeyValuePair(AUDIO_ECHO_CANCELLATION_CONSTRAINT, FALSE));
		audioConstraints.mandatory.add(
				new MediaConstraints.KeyValuePair(AUDIO_AUTO_GAIN_CONTROL_CONSTRAINT, FALSE));
		audioConstraints.mandatory.add(
				new MediaConstraints.KeyValuePair(AUDIO_HIGH_PASS_FILTER_CONSTRAINT, FALSE));
		audioConstraints.mandatory.add(
				new MediaConstraints.KeyValuePair(AUDIO_NOISE_SUPPRESSION_CONSTRAINT, FALSE));


		// Create SDP constraints.
		sdpMediaConstraints = new MediaConstraints();
		sdpMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true")); 
		sdpMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true")); 
	}

	public void addIceCandidate(IceCandidate iceCandidate) {
		signallingExecutor.execute(() -> {
			if (descriptionReady) {
				if (!peerConnection.addIceCandidate(iceCandidate)) 
				{
					logger.error("Cannot add ice candidate({}) for stream {}", iceCandidate, getStreamId());
				}	
			}
			else {
				iceCandidateQueue.add(iceCandidate);
				logger.warn("Ice candidate will be added later {}", iceCandidate);
			}
		});
	}

	public CapturerObserver getVideoObserver() {
		return capturerObserver;
	}

	public synchronized void stop() {
		if (isStopped) {
			logger.info("WebRTCManager is already stopped. Hash:{}", WebRTCManager.this.hashCode());
			return;
		}
		isStopped = true;

		signallingExecutor.execute(() -> {
			websocket.close();
			logger.info("WebRTCManager stopping. Hash: {}", WebRTCManager.this.hashCode());
			peerConnection.dispose();
			peerConnection = null;
			peerConnectionFactory.dispose();
			peerConnectionFactory = null;
			streamManager.stop();
			logger.info("WebRTCManager stopping leaving for {} Hash: {}", streamId, WebRTCManager.this.hashCode());
		});
	}

	public VirtualVideoEncoder getEncoder() {
		return encoderFactory.getEncoder();
	}

	@Override
	public void onCreateSuccess(SessionDescription sdp) {

		//System.out.println("******************\nonCreateSuccess\n"+sdp.description+"\n**************************");

		signallingExecutor.execute(() -> {
			logger.info("0 onCreateSuccess for {}", getStreamId());

			if (sdp.type == Type.ANSWER) {  //this is webrtc player

				peerConnection.setLocalDescription(new SdpObserver() {

					@Override
					public void onSetSuccess() {
						for (Iterator<IceCandidate> iterator = iceCandidateQueue.iterator(); iterator.hasNext();) {
							IceCandidate iceCandidate = iterator.next();

							if (!peerConnection.addIceCandidate(iceCandidate)) {
								logger.error("Candidate cannot be added {}", iceCandidate);
							}

							iterator.remove();
						}
						descriptionReady = true;
						websocket.sendSDPConfiguration(sdp.description, "answer", getStreamId());
					}

					@Override
					public void onSetFailure(String error) {
						logger.error("Cannot set local description for {} error:", getStreamId(), error);				
					}

					@Override
					public void onCreateSuccess(SessionDescription sdp) {
						// no need
					}

					@Override
					public void onCreateFailure(String error) {
						// no need
					}
				}, sdp);
			}
			else  {  //this is webrtc publisher

				peerConnection.setLocalDescription(new SdpObserver() {
					@Override
					public void onSetSuccess() {
						logger.info("local SDP is set");
						websocket.sendSDPConfiguration(sdp.description, "offer", getStreamId());
					}
					@Override
					public void onSetFailure(String error) {
						logger.error("Cannot set local description for {} error: ", getStreamId(), error);	
					}
					@Override
					public void onCreateSuccess(SessionDescription sdp) {}
					@Override
					public void onCreateFailure(String error) {}
				}, sdp);
			}
			logger.info("1 onCreateSuccess for {}", getStreamId());
		});
	}

	@Override
	public void onSetSuccess() {
		signallingExecutor.execute(() -> {
			logger.info("onSetSuccess for {}", getStreamId());
			if(streamManager instanceof WebRTCPlayer)  //  sdp.type == Type.OFFER) 
			{
				peerConnection.createAnswer(this, sdpMediaConstraints);
			}
			else if (streamManager instanceof WebRTCPublisher) {
				for (Iterator<IceCandidate> iterator = iceCandidateQueue.iterator(); iterator.hasNext();) {
					IceCandidate iceCandidate = iterator.next();

					if (!peerConnection.addIceCandidate(iceCandidate)) {
						logger.error("Candidate cannot be added {}", iceCandidate);
					}

					iterator.remove();
				}
				descriptionReady = true;
			}
		});
	}

	@Override
	public void onCreateFailure(String error) {
		logger.info("onCreateFailure: {} " , error);		
	}

	@Override
	public void onSetFailure(String error) {
		logger.info("onSetFailure: {} " , error);		
	}

	@Override
	public void onSignalingChange(SignalingState newState) {
		logger.info("onSignalingChange new state: {}" , newState);		
	}

	@Override
	public void onIceConnectionChange(IceConnectionState newState) {
		signallingExecutor.execute(() -> {
			logger.info("0 onIceConnectionChange {}  instance:{} time:{}" , newState, WebRTCManager.this.hashCode(), System.currentTimeMillis());
			if (newState == IceConnectionState.CONNECTED) {

				if (connected) {
					logger.info("it's already connected, not starting again. Hash:{}", WebRTCManager.this.hashCode());
					return;
				}
				connected  = true;
				if(settings.mode == Mode.PLAYER) {
					streamManager.start();
					listener.onCompleted();
				}
			}
			else if (newState == IceConnectionState.COMPLETED) {
				if(settings.mode == Mode.PUBLISHER) {
					streamManager.start();
					listener.onCompleted();
				}
			}
			else if (newState == IceConnectionState.DISCONNECTED || newState == IceConnectionState.FAILED
					|| newState == IceConnectionState.CLOSED) 
			{	
				streamManager.stop();
				stop();
				listener.onCompleted();
			}
			logger.info("1 onIceConnectionChange {} instance:{}" , newState, WebRTCManager.this.hashCode());
		});

	}

	@Override
	public void onIceConnectionReceivingChange(boolean receiving) {
		logger.info("onIceConnectionReceivingChange new state: {}" , receiving);
	}

	@Override
	public void onIceGatheringChange(IceGatheringState newState) {
		logger.info("onIceGatheringChange new state: {}" , newState);
	}

	@Override
	public void onIceCandidate(IceCandidate candidate) {
		
		signallingExecutor.execute(() -> {
			logger.info("0 onIceCandidate candidate: {} time: {}" , candidate, System.currentTimeMillis());
			websocket.sendTakeCandidateMessage(candidate.sdpMLineIndex,	candidate.sdpMid, candidate.sdp, getStreamId());
			logger.info("1 onIceCandidate candidate: {}" , candidate);
		});
		
	}

	@Override
	public void onIceCandidatesRemoved(IceCandidate[] candidates) {
		logger.info("onIceCandidatesRemoved: {}" , candidates);
	}

	@Override
	public void onAddStream(MediaStream stream) {
		logger.info("onAddStream for streamId {}", getStreamId());

		if (!stream.videoTracks.isEmpty()) {
			VideoTrack videoTrack = stream.videoTracks.get(0);
			if (videoTrack != null) {
				/*
				 * no need to video sink because we get data with decoder
				 */
			}
		}
	}

	@Override
	public void onRemoveStream(MediaStream stream) {
		logger.info("onRemoveStream for stream Id {}", getStreamId());		
	}

	@Override
	public void onDataChannel(DataChannel dataChannel) {
		logger.info("onDataChannel for stream Id {}", getStreamId());
		this.dataChannel = dataChannel;
		if(settings.dataChannel) {
			dataChannel.registerObserver(new DataChannel.Observer() {
				@Override
				public void onStateChange() {
					logger.info("DataChannel State Change for stream Id {} state:{}", streamId, dataChannel.state());
					if(dataChannel.state() == State.CLOSED) {
						dataChannel.dispose();
						WebRTCManager.this.dataChannel = null;
					}
				}

				@Override
				public void onMessage(org.webrtc.DataChannel.Buffer buffer) {
					logger.debug("DataChannel message received stream Id {}", streamId);
					byte[] data = new byte[buffer.data.capacity()];
					buffer.data.get(data);
					listener.onDataChannelMessage(new String(data));
				}

				@Override
				public void onBufferedAmountChange(long previousAmount) {
					logger.debug("DataChannel Buffered Amount Change Id {}", streamId);
				}
			});
		}
		else {
			logger.warn("DataChannel is closed because it is not enabled");
			dataChannel.close();
			dataChannel.dispose();
		}
	}

	@Override
	public void onRenegotiationNeeded() {
		logger.info("onRenegotiationNeeded for stream Id {}", getStreamId());
	}

	@Override
	public void onAddTrack(RtpReceiver receiver, MediaStream[] mediaStreams) {
		logger.info("onAddTrack for streamId {}", getStreamId());
	}

	public void setStreamManager(StreamManager streamManager) {
		this.streamManager = streamManager;		
	}

	public WebRtcAudioRecord getAudioRecord() {
		return audioRecord;
	}

	public String getStreamId() {
		return streamId;
	}

	public void setStreamId(String streamId) {
		this.streamId = streamId;
	}

	public void start() {
		initPeerConnection();

		websocket.connect();
	}

	public VirtualVideoDecoder getDecoder() {
		return decoderFactory.getDecoder();
	}

	public WebRtcAudioTrack getAudioTrack() {
		return webRtcAudioTrack;
	}

	public void setListener(IWebRTCEventListerner listener) {
		this.listener = listener;
	}

	public StreamManager getStreamManager() {
		return streamManager;
	}

	public void sendDataChannelMessage(String message) {
		if(dataChannel != null) {
			Buffer buffer = new Buffer(ByteBuffer.wrap(message.getBytes()), false);
			signallingExecutor.execute(() -> dataChannel.send(buffer));
		}
	}
}
