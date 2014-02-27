/*
============================================================================ 
Licensed Materials - Property of IBM

5747-SM3
 
(C) Copyright IBM Corp. 1999, 2012 All Rights Reserved.
 
US Government Users Restricted Rights - Use, duplication or
disclosure restricted by GSA ADP Schedule Contract with
IBM Corp.
============================================================================
 */
package com.ibm.msg.android.service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttAsyncClient;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClientPersistence;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttPersistenceException;
import org.eclipse.paho.client.mqttv3.MqttSecurityException;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.util.SparseArray;

/**
 * <p>
 * Implementation of the MQTT asynchronous client interface, using the MQTT
 * Android service to actually interface with MQTT.
 * </p>
 */
public class MqttClientAndroidService extends BroadcastReceiver implements
    IMqttAsyncClient {

  private static final String SERVICE_NAME = "com.ibm.msg.android.service.MqttService";

  private static final int BIND_SERVICE_FLAG = 0;

  private static ExecutorService pool = Executors.newCachedThreadPool();

  /**
   * ServiceConnection to process when we bind to our service
   */
  private final class MyServiceConnection implements ServiceConnection {

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
      mqttService = ((MqttServiceBinder) binder).getService();

      // now that we have the service available, we can actually
      // connect...
      doConnect();
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
      mqttService = null;
    }
  }

  // Listener for when the service is connected or disconnected
  private MyServiceConnection serviceConnection = new MyServiceConnection();

  // The Android Service which will process our mqtt calls
  private MqttService mqttService;

  // An identifier for the underlying client connection, which we can pass to
  // the service
  private String clientHandle;

  Context myContext;

  // We hold the various tokens in a collection and pass identifiers for them
  // to the service
  private SparseArray<IMqttToken> tokenMap = new SparseArray<IMqttToken>();
  private int tokenNumber = 0;

  // Connection data
  private String serverURI;
  private String clientId;
  private MqttClientPersistence persistence = null;
  private MqttConnectOptions connectOptions;
  private IMqttToken connectToken;

  // The MqttCallback provided by the application
  private MqttCallback callback;

  /**
   * Constructor
   * 
   * @param context
   * @param serverURI
   *            specifies the protocol, host name and port to be used to
   *            connect to an MQTT server
   * @param clientId
   *            specifies the name by which this connection should be
   *            identified to the server
   */
  public MqttClientAndroidService(Context context, String serverURI,
      String clientId) {
    this(context, serverURI, clientId, null);
  }

  /**
   * constructor
   * 
   * @param context
   * @param serverURI
   *            specifies the protocol, host name and port to be used to
   *            connect to an MQTT server
   * @param clientId
   *            specifies the name by which this connection should be
   *            identified to the server
   * @param persistence
   */
  public MqttClientAndroidService(Context context, String serverURI,
      String clientId, MqttClientPersistence persistence) {
    myContext = context;
    this.serverURI = serverURI;
    this.clientId = clientId;
    this.persistence = persistence;
  }

  /**
   * @return whether or not we are connected
   */
  @Override
  public boolean isConnected() {
    return mqttService.isConnected(clientHandle);
  }

  /**
   * @return the clientId by which we identify ourself to the mqtt server
   */
  @Override
  public String getClientId() {
    return clientId;
  }

  /**
   * @return the mqtt server URI
   */
  @Override
  public String getServerURI() {
    return serverURI;
  }

  /**
   * @see org.eclipse.paho.client.mqttv3.IMqttAsyncClient#close()
   */
  @Override
  public void close() {
    // Nothing to do TODO should pass this over to paho
  }

  /**
   * @see org.eclipse.paho.client.mqttv3.IMqttAsyncClient#connect()
   */
  @Override
  public IMqttToken connect() throws MqttException {
    return connect(null, null);
  }

  /**
   * @see org.eclipse.paho.client.mqttv3.IMqttAsyncClient#connect(MqttConnectOptions)
   */
  @Override
  public IMqttToken connect(MqttConnectOptions options) throws MqttException {
    return connect(options, null, null);
  }

  /**
   * @see org.eclipse.paho.client.mqttv3.IMqttAsyncClient#connect(Object,
   *      IMqttActionListener)
   */
  @Override
  public IMqttToken connect(Object userContext, IMqttActionListener callback)
      throws MqttException {
    return connect(new MqttConnectOptions(), userContext, callback);
  }

  /**
   * @see org.eclipse.paho.client.mqttv3.IMqttAsyncClient#connect(Object,
   *      IMqttActionListener)
   */
  /*
   * The actual connection depends on the service, which we start and bind to
   * here, but which we can't actually use until the serviceConnection
   * onServiceConnected() method has run (asynchronously), so the connection
   * itself takes place in the onServiceConnected() method
   */
  @Override
  public IMqttToken connect(MqttConnectOptions options, Object userContext,
      IMqttActionListener callback) throws MqttException {

    IMqttToken token = new MqttTokenAndroidService(this, userContext,
        callback);

    connectOptions = options;
    connectToken = token;

    /*
     * The actual connection depends on the service, which we start and bind
     * to here, but which we can't actually use until the serviceConnection
     * onServiceConnected() method has run (asynchronously), so the
     * connection itself takes place in the onServiceConnected() method
     */
    if (mqttService == null) { // First time - must bind to the service
      Intent serviceStartIntent = new Intent();
      serviceStartIntent.setClassName(myContext, SERVICE_NAME);
      Object service = myContext.startService(serviceStartIntent);
      if (service == null) {
        IMqttActionListener listener = token.getActionCallback();
        if (listener != null) {
          listener.onFailure(token, new RuntimeException(
              "cannot start service " + SERVICE_NAME));
        }
      }

      // We bind with BIND_SERVICE_FLAG (0), leaving us the manage the lifecycle
      // until the last time it is stopped by a call to stopService()
      myContext.bindService(serviceStartIntent, serviceConnection,
          BIND_SERVICE_FLAG);

      IntentFilter filter = new IntentFilter();
      filter.addAction(MqttServiceConstants.CALLBACK_TO_ACTIVITY);
      myContext.registerReceiver(this, filter);
    }
    else {
      pool.execute(new Runnable() {

        @Override
        public void run() {
          doConnect();

        }

      });
    }

    return token;
  }

  /**
   * Actually do the mqtt connect operation
   */
  private void doConnect() {
    if (clientHandle == null) {
      clientHandle = mqttService.getClient(serverURI, clientId,
          persistence);
    }
    String activityToken = storeToken(connectToken);
    try {
      mqttService.connect(clientHandle, connectOptions, null,
          activityToken);
    }
    catch (MqttException e) {
      IMqttActionListener listener = connectToken.getActionCallback();
      if (listener != null) {
        listener.onFailure(connectToken, e);
      }
    }
  }

  /**
   * @see org.eclipse.paho.client.mqttv3.IMqttAsyncClient#disconnect()
   */
  @Override
  public IMqttToken disconnect() throws MqttException {
    IMqttToken token = new MqttTokenAndroidService(this, null,
        (IMqttActionListener) null);
    String activityToken = storeToken(token);
    mqttService.disconnect(clientHandle, null, activityToken);
    return token;
  }

  /**
   * @see org.eclipse.paho.client.mqttv3.IMqttAsyncClient#disconnect(long)
   */
  @Override
  public IMqttToken disconnect(long quiesceTimeout) throws MqttException {
    IMqttToken token = new MqttTokenAndroidService(this, null,
        (IMqttActionListener) null);
    String activityToken = storeToken(token);
    mqttService.disconnect(clientHandle, quiesceTimeout, null,
        activityToken);
    return token;
  }

  /**
   * @see org.eclipse.paho.client.mqttv3.IMqttAsyncClient#disconnect(Object,
   *      IMqttActionListener)
   */
  @Override
  public IMqttToken disconnect(Object userContext,
      IMqttActionListener callback) throws MqttException {
    IMqttToken token = new MqttTokenAndroidService(this, userContext,
        callback);
    String activityToken = storeToken(token);
    mqttService.disconnect(clientHandle, null, activityToken);
    return token;
  }

  /**
   * @see org.eclipse.paho.client.mqttv3.IMqttAsyncClient#disconnect(long,
   *      Object, IMqttActionListener)
   */
  @Override
  public IMqttToken disconnect(long quiesceTimeout, Object userContext,
      IMqttActionListener callback) throws MqttException {
    IMqttToken token = new MqttTokenAndroidService(this, userContext,
        callback);
    String activityToken = storeToken(token);
    mqttService.disconnect(clientHandle, quiesceTimeout, null,
        activityToken);
    return token;
  }

  /**
   * @see org.eclipse.paho.client.mqttv3.IMqttAsyncClient#publish(String,
   *      byte[], int, boolean)
   */
  @Override
  public IMqttDeliveryToken publish(String topic, byte[] payload, int qos,
      boolean retained) throws MqttException, MqttPersistenceException {
    return publish(topic, payload, qos, retained, null, null);
  }

  /**
   * @see org.eclipse.paho.client.mqttv3.IMqttAsyncClient#publish(String,
   *      MqttMessage)
   */
  @Override
  public IMqttDeliveryToken publish(String topic, MqttMessage message)
      throws MqttException, MqttPersistenceException {
    return publish(topic, message, null, null);
  }

  /**
   * @see org.eclipse.paho.client.mqttv3.IMqttAsyncClient#publish(String,
   *      byte[], int, boolean, Object, IMqttActionListener)
   */
  @Override
  public IMqttDeliveryToken publish(String topic, byte[] payload, int qos,
      boolean retained, Object userContext, IMqttActionListener callback)
      throws MqttException, MqttPersistenceException {

    MqttMessage message = new MqttMessage(payload);
    message.setQos(qos);
    message.setRetained(retained);
    MqttDeliveryTokenAndroidService token = new MqttDeliveryTokenAndroidService(
        this, userContext, callback, message);
    String activityToken = storeToken(token);
    IMqttDeliveryToken internalToken = mqttService.publish(clientHandle,
        topic, payload, qos, retained, null, activityToken);
    token.setDelegate(internalToken);
    return token;
  }

  /**
   * @see org.eclipse.paho.client.mqttv3.IMqttAsyncClient#publish(String,
   *      MqttMessage, Object, IMqttActionListener)
   */
  @Override
  public IMqttDeliveryToken publish(String topic, MqttMessage message,
      Object userContext, IMqttActionListener callback)
      throws MqttException, MqttPersistenceException {
    MqttDeliveryTokenAndroidService token = new MqttDeliveryTokenAndroidService(
        this, userContext, callback, message);
    String activityToken = storeToken(token);
    IMqttDeliveryToken internalToken = mqttService.publish(clientHandle,
        topic, message, null, activityToken);
    token.setDelegate(internalToken);
    return token;
  }

  /**
   * @see org.eclipse.paho.client.mqttv3.IMqttAsyncClient#subscribe(String,
   *      int)
   */
  @Override
  public IMqttToken subscribe(String topic, int qos) throws MqttException,
      MqttSecurityException {
    return subscribe(topic, qos, null, null);
  }

  /**
   * @see org.eclipse.paho.client.mqttv3.IMqttAsyncClient#subscribe(String[],
   *      int[])
   */
  @Override
  public IMqttToken subscribe(String[] topic, int[] qos)
      throws MqttException, MqttSecurityException {
    return subscribe(topic, qos, null, null);
  }

  /**
   * @see org.eclipse.paho.client.mqttv3.IMqttAsyncClient#subscribe(String,
   *      int, Object, IMqttActionListener)
   */
  @Override
  public IMqttToken subscribe(String topic, int qos, Object userContext,
      IMqttActionListener callback) throws MqttException {
    IMqttToken token = new MqttTokenAndroidService(this, userContext,
        callback, new String[]{topic});
    String activityToken = storeToken(token);
    mqttService.subscribe(clientHandle, topic, qos, null, activityToken);
    return token;
  }

  /**
   * @see org.eclipse.paho.client.mqttv3.IMqttAsyncClient#subscribe(String[],
   *      int[], Object, IMqttActionListener)
   */
  @Override
  public IMqttToken subscribe(String[] topic, int[] qos, Object userContext,
      IMqttActionListener callback) throws MqttException {
    IMqttToken token = new MqttTokenAndroidService(this, userContext,
        callback, topic);
    String activityToken = storeToken(token);
    mqttService.subscribe(clientHandle, topic, qos, null, activityToken);
    return token;
  }

  /**
   * @see org.eclipse.paho.client.mqttv3.IMqttAsyncClient#unsubscribe(String)
   */
  @Override
  public IMqttToken unsubscribe(String topic) throws MqttException {
    return unsubscribe(topic, null, null);
  }

  /**
   * @see org.eclipse.paho.client.mqttv3.IMqttAsyncClient#unsubscribe(String[])
   */
  @Override
  public IMqttToken unsubscribe(String[] topic) throws MqttException {
    return unsubscribe(topic, null, null);
  }

  /**
   * @see org.eclipse.paho.client.mqttv3.IMqttAsyncClient#unsubscribe(String,
   *      Object, IMqttActionListener)
   */
  @Override
  public IMqttToken unsubscribe(String topic, Object userContext,
      IMqttActionListener callback) throws MqttException {
    IMqttToken token = new MqttTokenAndroidService(this, userContext,
        callback);
    String activityToken = storeToken(token);
    mqttService.unsubscribe(clientHandle, topic, null, activityToken);
    return token;
  }

  /**
   * @see org.eclipse.paho.client.mqttv3.IMqttAsyncClient#unsubscribe(String[],
   *      Object, IMqttActionListener)
   */
  @Override
  public IMqttToken unsubscribe(String[] topic, Object userContext,
      IMqttActionListener callback) throws MqttException {
    IMqttToken token = new MqttTokenAndroidService(this, userContext,
        callback);
    String activityToken = storeToken(token);
    mqttService.unsubscribe(clientHandle, topic, null, activityToken);
    return token;
  }

  /**
   * @see org.eclipse.paho.client.mqttv3.IMqttAsyncClient#getPendingDeliveryTokens
   *      ()
   */
  @Override
  public IMqttDeliveryToken[] getPendingDeliveryTokens() {
    return mqttService.getPendingDeliveryTokens(clientHandle);
  }

  /**
   * @see org.eclipse.paho.client.mqttv3.IMqttAsyncClient#setCallback(MqttCallback)
   */
  @Override
  public void setCallback(MqttCallback callback) {
    this.callback = callback;

  }

  /**
   * <p>
   * Process incoming Intent objects representing the results of operations
   * and asynchronous activities such as message received
   * </p>
   * <p>
   * <strong>Note:</strong> This is only a public method because the Android
   * APIs require such.<br>
   * This method should not be explicitly invoked.
   * </p>
   */
  @Override
  public void onReceive(Context context, Intent intent) {
    Bundle data = intent.getExtras();

    String handleFromIntent = data
        .getString(MqttServiceConstants.CALLBACK_CLIENT_HANDLE);

    if ((handleFromIntent == null)
        || (!handleFromIntent.equals(clientHandle))) {
      return;
    }

    String action = data.getString(MqttServiceConstants.CALLBACK_ACTION);

    if (action.equals(MqttServiceConstants.CONNECT_ACTION)) {
      connectAction(data);
    }
    else if (action.equals(MqttServiceConstants.MESSAGE_ARRIVED_ACTION)) {
      messageArrivedAction(data);
    }
    else if (action.equals(MqttServiceConstants.SUBSCRIBE_ACTION)) {
      subscribeAction(data);
    }
    else if (action.equals(MqttServiceConstants.UNSUBSCRIBE_ACTION)) {
      unSubscribeAction(data);
    }
    else if (action.equals(MqttServiceConstants.SEND_ACTION)) {
      sendAction(data);
    }
    else if (action.equals(MqttServiceConstants.MESSAGE_DELIVERED_ACTION)) {
      messageDeliveredAction(data);
    }
    else if (action
        .equals(MqttServiceConstants.ON_CONNECTION_LOST_ACTION)) {
      connectionLostAction(data);
    }
    else if (action.equals(MqttServiceConstants.DISCONNECT_ACTION)) {
      disconnected(data);
    }

  }

  /**
   * Process the results of a connection
   * 
   * @param data
   */
  private void connectAction(Bundle data) {
    IMqttToken token = removeMqttToken(data);
    simpleAction(token, data);
  }

  /**
   * Process a notification that we have disconnected
   * 
   * @param data
   */
  private void disconnected(Bundle data) {
    clientHandle = null; // avoid reuse!
    IMqttToken token = removeMqttToken(data);
    if (token != null) {
      ((MqttTokenAndroidService) token).notifyComplete();
    }
    if (callback != null) {
      callback.connectionLost(null);
    }
  }

  /**
   * Process a Connection Lost notification
   * 
   * @param data
   */
  private void connectionLostAction(Bundle data) {
    if (callback != null) {
      Exception reason = (Exception) data
          .getSerializable(MqttServiceConstants.CALLBACK_EXCEPTION);
      callback.connectionLost(reason);
    }
  }

  /**
   * Common processing for many notifications
   * 
   * @param token
   *            the token associated with the action being undertake
   * @param data
   *            the result data
   */
  private void simpleAction(IMqttToken token, Bundle data) {
    if (token != null) {
      Status status = (Status) data
          .getSerializable(MqttServiceConstants.CALLBACK_STATUS);
      if (status == Status.OK) {
        ((MqttTokenAndroidService) token).notifyComplete();
      }
      else {

        Exception exceptionThrown = (Exception) data.getSerializable(MqttServiceConstants.CALLBACK_EXCEPTION);
        ((MqttTokenAndroidService) token)
            .notifyFailure(exceptionThrown);
      }
    }
  }

  /**
   * Process notification of a publish(send) operation
   * 
   * @param data
   */
  private void sendAction(Bundle data) {
    IMqttToken token = getMqttToken(data); // get, don't remove - will
    // remove on delivery
    simpleAction(token, data);
  }

  /**
   * Process notification of a subscribe operation
   * 
   * @param data
   */
  private void subscribeAction(Bundle data) {
    IMqttToken token = removeMqttToken(data);
    simpleAction(token, data);
  }

  /**
   * Process notification of an unsubscribe operation
   * 
   * @param data
   */
  private void unSubscribeAction(Bundle data) {
    IMqttToken token = removeMqttToken(data);
    simpleAction(token, data);
  }

  /**
   * Process notification of a published message having been delivered
   * 
   * @param data
   */
  private void messageDeliveredAction(Bundle data) {
    IMqttToken token = removeMqttToken(data);
    if (token != null) {
      if (callback != null) {
        Status status = (Status) data
            .getSerializable(MqttServiceConstants.CALLBACK_STATUS);
        if (status == Status.OK) {
          callback.deliveryComplete((IMqttDeliveryToken) token);
        }
      }
    }
  }

  /**
   * Process notification of a message's arrival
   * 
   * @param data
   */
  private void messageArrivedAction(Bundle data) {
    if (callback != null) {
      String messageId = data
          .getString(MqttServiceConstants.CALLBACK_MESSAGE_ID);
      String destinationName = data
          .getString(MqttServiceConstants.CALLBACK_DESTINATION_NAME);

      ParcelableMqttMessage message = (ParcelableMqttMessage) data
          .getParcelable(MqttServiceConstants.CALLBACK_MESSAGE_PARCEL);
      try {
        callback.messageArrived(destinationName, message);
        mqttService.acknowledgeMessageArrival(clientHandle, messageId);
        // let the service discard the saved message details
      }
      catch (Exception e) {
        // Swallow the exception
      }
    }
  }

  /**
   * @param token
   *            identifying an operation
   * @return an identifier for the token which can be passed to the Android
   *         Service
   */
  private synchronized String storeToken(IMqttToken token) {
    tokenMap.put(tokenNumber, token);
    return Integer.toString(tokenNumber++);
  }

  /**
   * Get a token identified by a string, and remove it from our map
   * 
   * @param data
   * @return the token
   */
  private synchronized IMqttToken removeMqttToken(Bundle data) {
    String activityToken = data
        .getString(MqttServiceConstants.CALLBACK_ACTIVITY_TOKEN);
    int tokenNumber = Integer.parseInt(activityToken);
    IMqttToken token = tokenMap.get(tokenNumber);
    tokenMap.delete(tokenNumber);
    return token;
  }

  /**
   * Get a token identified by a string, and remove it from our map
   * 
   * @param data
   * @return the token
   */
  private synchronized IMqttToken getMqttToken(Bundle data) {
    String activityToken = data
        .getString(MqttServiceConstants.CALLBACK_ACTIVITY_TOKEN);
    IMqttToken token = tokenMap.get(Integer.parseInt(activityToken));
    return token;
  }
}
