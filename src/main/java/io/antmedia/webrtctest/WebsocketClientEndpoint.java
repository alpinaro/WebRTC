package io.antmedia.webrtctest;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.websocket.ClientEndpoint;
import javax.websocket.CloseReason;
import javax.websocket.ContainerProvider;
import javax.websocket.EndpointConfig;
import javax.websocket.MessageHandler;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.webrtc.IceCandidate;
import org.webrtc.SessionDescription;
import org.webrtc.SessionDescription.Type;

import io.antmedia.websocket.WebSocketConstants;


@ClientEndpoint
public class WebsocketClientEndpoint {

	private static Logger logger = LoggerFactory.getLogger(WebsocketClientEndpoint.class);
	private JSONParser jsonParser = new JSONParser();
	WebRTCManager webrtcManager;
	private Session session;
	private URI uri;

	public WebsocketClientEndpoint(URI endpointURI) {
		this.uri = endpointURI;
	}

	public void connect() {
		System.out.println("connect");

		try {
			WebSocketContainer container = ContainerProvider.getWebSocketContainer();
			container.connectToServer(this, uri);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@OnOpen
	public void onOpen(Session session, EndpointConfig config)
	{
		logger.info("websocket opened");
		this.session = session;
		
		if(Settings.instance.mode == Mode.PUBLISHER) {
			sendPublish(webrtcManager.getStreamId());
		}
		else {
			sendPlay(webrtcManager.getStreamId());
		}
	}

	@OnClose
	public void onClose(Session session) {

	}

	@OnError
	public void onError(Session session, Throwable throwable) {

	}

	@OnMessage
	public void onMessage(Session session, String message) {
		System.out.println(message);
		try {

			if (message == null) {
				logger.error("Received message null for session id: {}" , session.getId());
				return;
			}

			JSONObject jsonObject = (JSONObject) jsonParser.parse(message);

			String cmd = (String) jsonObject.get(WebSocketConstants.COMMAND);
			if (cmd == null) {
				logger.error("Received message does not contain any command for session id: {}" , session.getId());
				return;
			}

			final String streamId = (String) jsonObject.get(WebSocketConstants.STREAM_ID);
			if (streamId == null || streamId.isEmpty()) 
			{
				sendNoStreamIdSpecifiedError();
				return;
			}
			if (cmd.equals(WebSocketConstants.START_COMMAND))  
			{
				webrtcManager.createOffer();
			}
			else if (cmd.equals(WebSocketConstants.TAKE_CONFIGURATION_COMMAND))  
			{
				processTakeConfigurationCommand(jsonObject, session.getId(), streamId);
			}
			else if (cmd.equals(WebSocketConstants.TAKE_CANDIDATE_COMMAND)) 
			{
				processTakeCandidateCommand(jsonObject, session.getId(), streamId);
			}
			else if (cmd.equals(WebSocketConstants.STOP_COMMAND)) {
			}
			else if (cmd.equals(WebSocketConstants.ERROR_COMMAND)) {
			}
			else if (cmd.equals(WebSocketConstants.NOTIFICATION_COMMAND)) {
			}
			else if (cmd.equals(WebSocketConstants.STREAM_INFORMATION_NOTIFICATION)) {
			}
			else if (cmd.equals(WebSocketConstants.PUBLISH_STARTED)) {
			}
		}
		catch (Exception e) {
			logger.error(e.getMessage());
		}

	}

	@SuppressWarnings("unchecked")
	public  void sendSDPConfiguration(String description, String type, String streamId) {

		sendMessage(getSDPConfigurationJSON (description, type,  streamId).toJSONString());
	}

	@SuppressWarnings("unchecked")
	public  void sendPublishStartedMessage(String streamId) {

		JSONObject jsonObj = new JSONObject();
		jsonObj.put(WebSocketConstants.COMMAND, WebSocketConstants.NOTIFICATION_COMMAND);
		jsonObj.put(WebSocketConstants.DEFINITION, WebSocketConstants.PUBLISH_STARTED);
		jsonObj.put(WebSocketConstants.STREAM_ID, streamId);

		sendMessage(jsonObj.toJSONString());
	}

	@SuppressWarnings("unchecked")
	public  void sendPublishFinishedMessage(String streamId) {
		JSONObject jsonObject = new JSONObject();
		jsonObject.put(WebSocketConstants.COMMAND, WebSocketConstants.NOTIFICATION_COMMAND);
		jsonObject.put(WebSocketConstants.DEFINITION,  WebSocketConstants.PUBLISH_FINISHED);
		jsonObject.put(WebSocketConstants.STREAM_ID, streamId);

		sendMessage(jsonObject.toJSONString());
	}

	@SuppressWarnings("unchecked")
	public  void sendStartMessage(String streamId) 
	{
		JSONObject jsonObject = new JSONObject();
		jsonObject.put(WebSocketConstants.COMMAND, WebSocketConstants.START_COMMAND);
		jsonObject.put(WebSocketConstants.STREAM_ID, streamId);

		sendMessage(jsonObject.toJSONString());
	}



	@SuppressWarnings("unchecked")
	protected  final  void sendNoStreamIdSpecifiedError()  {
		JSONObject jsonResponse = new JSONObject();
		jsonResponse.put(WebSocketConstants.COMMAND, WebSocketConstants.ERROR_COMMAND);
		jsonResponse.put(WebSocketConstants.DEFINITION, WebSocketConstants.NO_STREAM_ID_SPECIFIED);
		sendMessage(jsonResponse.toJSONString());	
	}

	@SuppressWarnings("unchecked")
	protected  final  void sendPlay(String streamId)  {
		JSONObject jsonResponse = new JSONObject();
		jsonResponse.put(WebSocketConstants.COMMAND, WebSocketConstants.PLAY_COMMAND);
		jsonResponse.put(WebSocketConstants.STREAM_ID, streamId);
		sendMessage(jsonResponse.toJSONString());	
	}

	@SuppressWarnings("unchecked")
	protected  final  void sendPublish(String streamId)  {
		JSONObject jsonResponse = new JSONObject();
		jsonResponse.put(WebSocketConstants.COMMAND, WebSocketConstants.PUBLISH_COMMAND);
		jsonResponse.put(WebSocketConstants.STREAM_ID, streamId);
		jsonResponse.put(WebSocketConstants.VIDEO, true);
		jsonResponse.put(WebSocketConstants.AUDIO, true);
		sendMessage(jsonResponse.toJSONString());	
	}

	@SuppressWarnings("unchecked")
	public void sendTakeCandidateMessage(long sdpMLineIndex, String sdpMid, String sdp, String streamId)
	{
		sendMessage(getTakeCandidateJSON(sdpMLineIndex, sdpMid, sdp, streamId).toJSONString());
	}


	@SuppressWarnings("unchecked")
	public void sendMessage(String message) {
		synchronized (this) {
			if (session.isOpen()) {
				try {
					session.getBasicRemote().sendText(message);
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
	}


	public static JSONObject getTakeCandidateJSON(long sdpMLineIndex, String sdpMid, String sdp, String streamId) {

		JSONObject jsonObject = new JSONObject();
		jsonObject.put(WebSocketConstants.COMMAND,  WebSocketConstants.TAKE_CANDIDATE_COMMAND);
		jsonObject.put(WebSocketConstants.CANDIDATE_LABEL, sdpMLineIndex);
		jsonObject.put(WebSocketConstants.CANDIDATE_ID, sdpMid);
		jsonObject.put(WebSocketConstants.CANDIDATE_SDP, sdp);
		jsonObject.put(WebSocketConstants.STREAM_ID, streamId);

		return jsonObject;
	}

	public static JSONObject getSDPConfigurationJSON(String description, String type, String streamId) {

		JSONObject jsonResponseObject = new JSONObject();
		jsonResponseObject.put(WebSocketConstants.COMMAND, WebSocketConstants.TAKE_CONFIGURATION_COMMAND);
		jsonResponseObject.put(WebSocketConstants.SDP, description);
		jsonResponseObject.put(WebSocketConstants.TYPE, type);
		jsonResponseObject.put(WebSocketConstants.STREAM_ID, streamId);

		return jsonResponseObject;
	}

	private void processTakeConfigurationCommand(JSONObject jsonObject, String sessionId, String streamId) {
		String typeString = (String)jsonObject.get(WebSocketConstants.TYPE);
		String sdpDescription = (String)jsonObject.get(WebSocketConstants.SDP);

		SessionDescription.Type type;
		if (typeString.equals("offer")) {
			type = Type.OFFER;
		}
		else {
			type = Type.ANSWER;
		}


		SessionDescription sdp = new SessionDescription(type, sdpDescription);
		webrtcManager.setRemoteDescription(sdp);
	}

	private void processTakeCandidateCommand(JSONObject jsonObject, String sessionId, String streamId) {
		String sdpMid = (String) jsonObject.get(WebSocketConstants.CANDIDATE_ID);
		String sdp = (String) jsonObject.get(WebSocketConstants.CANDIDATE_SDP);
		long sdpMLineIndex = (long)jsonObject.get(WebSocketConstants.CANDIDATE_LABEL);

		IceCandidate iceCandidate = new IceCandidate(sdpMid, (int)sdpMLineIndex, sdp);
		webrtcManager.addIceCandidate(iceCandidate);

	}

	public void setManager(WebRTCManager manager) {
		this.webrtcManager = manager;
	}

}