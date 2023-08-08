/*
 * Copyright (c) 2010-2020 Nathan Rajlich
 *
 *  Permission is hereby granted, free of charge, to any person
 *  obtaining a copy of this software and associated documentation
 *  files (the "Software"), to deal in the Software without
 *  restriction, including without limitation the rights to use,
 *  copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the
 *  Software is furnished to do so, subject to the following
 *  conditions:
 *
 *  The above copyright notice and this permission notice shall be
 *  included in all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 *  EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 *  OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 *  NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 *  HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 *  WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 *  FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 *  OTHER DEALINGS IN THE SOFTWARE.
 */

package org.java_websocket;

import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.util.NamedThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Base class for additional implementations for the server as well as the client
 */
public abstract class AbstractWebSocket extends WebSocketAdapter {

  /**
   * Logger instance
   *
   * @since 1.4.0
   */
  private final Logger log = LoggerFactory.getLogger(AbstractWebSocket.class);

  /**
   * Attribute which allows you to deactivate the Nagle's algorithm
   *
   * @since 1.3.3
   */
  private boolean tcpNoDelay;

  /**
   * Attribute which allows you to enable/disable the SO_REUSEADDR socket option.
   *
   * @since 1.3.5
   */
  private boolean reuseAddr;

  /**
   * Determines the default value of the {@link java.net.SocketOptions#SO_LINGER} parameter
   * for newly created sockets.
   * <p>
   * Default: {@code -1}
   * </p>
   * @see java.net.SocketOptions#SO_LINGER
   * @since 1.5.4.update
   */
  private int soLinger = -1;

  private int sndBufSize = 0;

  private int rcvBufSize = 0;

  private int soTimeout = -1;

  /**
   * Attribute for a service that triggers lost connection checking
   *
   * @since 1.4.1
   */
  private ScheduledExecutorService connectionLostCheckerService;
  /**
   * Attribute for a task that checks for lost connections
   *
   * @since 1.4.1
   */
  private ScheduledFuture<?> connectionLostCheckerFuture;

  /**
   * Attribute for the lost connection check interval in nanoseconds
   *
   * @since 1.3.4
   */
  private long connectionLostTimeout = TimeUnit.SECONDS.toNanos(60);

  /**
   * Attribute to keep track if the WebSocket Server/Client is running/connected
   *
   * @since 1.3.9
   */
  private boolean websocketRunning = false;

  /**
   * Attribute to sync on
   */
  private final Object syncConnectionLost = new Object();

  /**
   * Get the interval checking for lost connections Default is 60 seconds
   *
   * @return the interval in seconds
   * @since 1.3.4
   */
  public int getConnectionLostTimeout() {
    synchronized (syncConnectionLost) {
      return (int) TimeUnit.NANOSECONDS.toSeconds(connectionLostTimeout);
    }
  }

  /**
   * Setter for the interval checking for lost connections A value lower or equal 0 results in the
   * check to be deactivated
   *
   * @param connectionLostTimeout the interval in seconds
   * @since 1.3.4
   */
  public void setConnectionLostTimeout(int connectionLostTimeout) {
    synchronized (syncConnectionLost) {
      this.connectionLostTimeout = TimeUnit.SECONDS.toNanos(connectionLostTimeout);
      if (this.connectionLostTimeout <= 0) {
        log.trace("Connection lost timer stopped");
        cancelConnectionLostTimer();
        return;
      }
      if (this.websocketRunning) {
        log.trace("Connection lost timer restarted");
        //Reset all the pings
        try {
          ArrayList<WebSocket> connections = new ArrayList<>(getConnections());
          WebSocketImpl webSocketImpl;
          for (WebSocket conn : connections) {
            if (conn instanceof WebSocketImpl) {
              webSocketImpl = (WebSocketImpl) conn;
              webSocketImpl.updateLastPong();
            }
          }
        } catch (Exception e) {
          log.error("Exception during connection lost restart", e);
        }
        restartConnectionLostTimer();
      }
    }
  }

  /**
   * Stop the connection lost timer
   *
   * @since 1.3.4
   */
  protected void stopConnectionLostTimer() {
    synchronized (syncConnectionLost) {
      if (connectionLostCheckerService != null || connectionLostCheckerFuture != null) {
        this.websocketRunning = false;
        log.trace("Connection lost timer stopped");
        cancelConnectionLostTimer();
      }
    }
  }

  /**
   * Start the connection lost timer
   *
   * @since 1.3.4
   */
  protected void startConnectionLostTimer() {
    synchronized (syncConnectionLost) {
      if (this.connectionLostTimeout <= 0) {
        log.trace("Connection lost timer deactivated");
        return;
      }
      log.trace("Connection lost timer started");
      this.websocketRunning = true;
      restartConnectionLostTimer();
    }
  }

  /**
   * This methods allows the reset of the connection lost timer in case of a changed parameter
   *
   * @since 1.3.4
   */
  private void restartConnectionLostTimer() {
    cancelConnectionLostTimer();
    connectionLostCheckerService = Executors
        .newSingleThreadScheduledExecutor(new NamedThreadFactory("connectionLostChecker"));
    Runnable connectionLostChecker = new Runnable() {

      /**
       * Keep the connections in a separate list to not cause deadlocks
       */
      private ArrayList<WebSocket> connections = new ArrayList<>();

      @Override
      public void run() {
        connections.clear();
        try {
          connections.addAll(getConnections());
          long minimumPongTime;
          synchronized (syncConnectionLost) {
            minimumPongTime = (long) (System.nanoTime() - (connectionLostTimeout * 1.5));
          }
          for (WebSocket conn : connections) {
            executeConnectionLostDetection(conn, minimumPongTime);
          }
        } catch (Exception e) {
          //Ignore this exception
        }
        connections.clear();
      }
    };

    connectionLostCheckerFuture = connectionLostCheckerService
        .scheduleAtFixedRate(connectionLostChecker, connectionLostTimeout, connectionLostTimeout,
            TimeUnit.NANOSECONDS);
  }

  /**
   * Send a ping to the endpoint or close the connection since the other endpoint did not respond
   * with a ping
   *
   * @param webSocket       the websocket instance
   * @param minimumPongTime the lowest/oldest allowable last pong time (in nanoTime) before we
   *                        consider the connection to be lost
   */
  private void executeConnectionLostDetection(WebSocket webSocket, long minimumPongTime) {
    if (!(webSocket instanceof WebSocketImpl)) {
      return;
    }
    WebSocketImpl webSocketImpl = (WebSocketImpl) webSocket;
    if (webSocketImpl.getLastPong() < minimumPongTime) {
      log.trace("Closing connection due to no pong received: {}", webSocketImpl);
      webSocketImpl.closeConnection(CloseFrame.ABNORMAL_CLOSE,
          "The connection was closed because the other endpoint did not respond with a pong in time. For more information check: https://github.com/TooTallNate/Java-WebSocket/wiki/Lost-connection-detection");
    } else {
      if (webSocketImpl.isOpen()) {
        webSocketImpl.sendPing();
      } else {
        log.trace("Trying to ping a non open connection: {}", webSocketImpl);
      }
    }
  }

  /**
   * Getter to get all the currently available connections
   *
   * @return the currently available connections
   * @since 1.3.4
   */
  protected abstract Collection<WebSocket> getConnections();

  /**
   * Cancel any running timer for the connection lost detection
   *
   * @since 1.3.4
   */
  private void cancelConnectionLostTimer() {
    if (connectionLostCheckerService != null) {
      connectionLostCheckerService.shutdownNow();
      connectionLostCheckerService = null;
    }
    if (connectionLostCheckerFuture != null) {
      connectionLostCheckerFuture.cancel(false);
      connectionLostCheckerFuture = null;
    }
  }

  /**
   * Tests if TCP_NODELAY is enabled.
   *
   * @return a boolean indicating whether or not TCP_NODELAY is enabled for new connections.
   * @since 1.3.3
   */
  public boolean isTcpNoDelay() {
    return tcpNoDelay;
  }

  /**
   * Setter for tcpNoDelay
   * <p>
   * Enable/disable TCP_NODELAY (disable/enable Nagle's algorithm) for new connections
   *
   * @param tcpNoDelay true to enable TCP_NODELAY, false to disable.
   * @since 1.3.3
   */
  public void setTcpNoDelay(boolean tcpNoDelay) {
    this.tcpNoDelay = tcpNoDelay;
  }

  /**
   * Tests Tests if SO_REUSEADDR is enabled.
   *
   * @return a boolean indicating whether or not SO_REUSEADDR is enabled.
   * @since 1.3.5
   */
  public boolean isReuseAddr() {
    return reuseAddr;
  }

  /**
   * Setter for soReuseAddr
   * <p>
   * Enable/disable SO_REUSEADDR for the socket
   *
   * @param reuseAddr whether to enable or disable SO_REUSEADDR
   * @since 1.3.5
   */
  public void setReuseAddr(boolean reuseAddr) {
    this.reuseAddr = reuseAddr;
  }


  /**
   * Determines the default value of the {@link java.net.SocketOptions#SO_LINGER} parameter
   * for newly created sockets.
   * <p>
   * Default: {@code -1}
   * </p>
   *
   * @return the default value of the {@link java.net.SocketOptions#SO_LINGER} parameter.
   * @see java.net.SocketOptions#SO_LINGER
   * @since 1.5.4.update
   */
  public int getSoLinger() {
    return soLinger;
  }

  /**
   * Determines the default value of the {@link java.net.SocketOptions#SO_LINGER} parameter
   * for newly created sockets.
   * <p>
   * Default: {@code -1}
   * </p>
   *
   * @see java.net.SocketOptions#SO_LINGER
   * @since 1.5.4.update
   */
  public void setSoLinger(int soLinger) {
    this.soLinger = soLinger;
  }

  /**
   * Determines the default value of the {@link java.net.SocketOptions#SO_SNDBUF} parameter
   * for newly created sockets.
   * <p>
   * Default: {@code 0} (system default)
   * </p>
   *
   * @return the default value of the {@link java.net.SocketOptions#SO_SNDBUF} parameter.
   * @see java.net.SocketOptions#SO_SNDBUF
   * @since 1.5.4.update
   */
  public int getSndBufSize() {
    return sndBufSize;
  }

  /**
   * Determines the default value of the {@link java.net.SocketOptions#SO_RCVBUF} parameter
   * for newly created sockets.
   * <p>
   * Default: {@code 0} (system default)
   * </p>
   *
   * @return the default value of the {@link java.net.SocketOptions#SO_RCVBUF} parameter.
   * @see java.net.SocketOptions#SO_RCVBUF
   * @since 1.5.4.update
   */
  public int getRcvBufSize() {
    return rcvBufSize;
  }

  /**
   * Determines the default value of the {@link java.net.SocketOptions#SO_SNDBUF} parameter
   * for newly created sockets.
   * <p>
   * Default: {@code 0} (system default)
   * </p>
   *
   * @see java.net.SocketOptions#SO_SNDBUF
   * @since 1.5.4.update
   */
  public void setSndBufSize(int sndBufSize) {
    this.sndBufSize = sndBufSize;
  }

  /**
   * Determines the default value of the {@link java.net.SocketOptions#SO_RCVBUF} parameter
   * for newly created sockets.
   * <p>
   * Default: {@code 0} (system default)
   * </p>
   *
   * @see java.net.SocketOptions#SO_RCVBUF
   * @since 1.5.4.update
   */
  public void setRcvBufSize(int rcvBufSize) {
    this.rcvBufSize = rcvBufSize;
  }

  /**
   * Determines the default socket timeout value for non-blocking I/O operations.
   * <p>
   * {@code 0} (no timeout)
   * </p>
   *
   * @return the default socket timeout value for non-blocking I/O operations.
   * @see java.net.SocketOptions#SO_TIMEOUT
   * @since 1.5.4.update
   */
  public int getSoTimeout() {
    return soTimeout;
  }

  /**
   * Determines the default socket timeout value for non-blocking I/O operations.
   * <p>
   * {@code 0} (no timeout)
   * </p>
   *
   * @see java.net.SocketOptions#SO_TIMEOUT
   * @since 1.5.4.update
   */
  public void setSoTimeout(int soTimeout) {
    this.soTimeout = soTimeout;
  }
}
