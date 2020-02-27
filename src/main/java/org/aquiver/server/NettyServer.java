/*
 * MIT License
 *
 * Copyright (c) 2019 1619kHz
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.aquiver.server;

import com.google.inject.AbstractModule;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.ResourceLeakDetector;
import org.aquiver.Aquiver;
import org.aquiver.Environment;
import org.aquiver.Systems;
import org.aquiver.BeanManager;
import org.aquiver.server.banner.Banner;
import org.aquiver.server.watcher.GlobalEnvListener;
import org.aquiver.server.watcher.GlobalEnvObserver;
import org.aquiver.server.websocket.WebSocketServerInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLException;
import java.io.File;
import java.security.cert.CertificateException;

import static org.aquiver.Const.*;

/**
 * 1, open ssl {@code initSSL}
 * 2, open webSocket {@code initWebSocket}
 * 3, open the Http com.crispy.service {@code startServer}
 * 4, observe the environment {@code watchEnv}
 * 5, configure the shutdown thread to stop the com.crispy.service hook {@code shutdownHook}
 *
 * @author WangYi
 * @since 2019/6/5
 */
public class NettyServer extends AbstractModule implements Server {

  private static final Logger log = LoggerFactory.getLogger(NettyServer.class);

  private final    Banner          defaultBanner   = new NettyServerBanner();
  private final    ServerBootstrap serverBootstrap = new ServerBootstrap();
  private volatile boolean         stop            = false;
  private          EventLoopGroup  bossGroup;
  private          EventLoopGroup  workerGroup;
  private          Environment     environment;
  private          Aquiver         aquiver;
  private          Channel         channel;
  private          SslContext      sslContext;

  /**
   * start server and init setting
   *
   * @param aquiver
   * @throws Exception
   */
  @Override
  public void start(Aquiver aquiver) throws Exception {
    long startMs = System.currentTimeMillis();

    this.aquiver     = aquiver;
    this.environment = aquiver.environment();
    this.printBanner();

    final String bootClsName     = aquiver.bootClsName();
    final String bootConfName    = aquiver.bootConfName();
    final String envName         = aquiver.envName();
    final String deviceName      = Systems.getDeviceName();
    final String currentUserName = System.getProperty("user.name");
    final String pidCode         = Systems.getPid();

    log.info("Starting {} on {} with PID {} ", bootClsName, deviceName + "/" + currentUserName, pidCode);
    log.info("Starting service [Netty]");
    log.info("Starting Http Server: Netty/4.1.36.Final");
    log.info("The configuration file loaded by this application startup is {}", bootConfName);

    this.configLoadLog(aquiver, envName);

    this.initIoc();
    this.initSSL();
    this.initWebSocket();
    this.startServer(startMs);
    this.watchEnv();
    this.shutdownHook();
  }

  /**
   * record the log loaded by the configuration
   *
   * @param crispy
   * @param envName
   */
  private void configLoadLog(Aquiver crispy, String envName) {
    if (crispy.masterConfig()) {
      log.info("Configuration information is loaded");
    }
    if (!crispy.envConfig()) {
      log.info("No active profile set, falling back to default profiles: default");
    }
    log.info("The application startup env is: {}", envName);
  }

  /**
   * init ioc container
   */
  private void initIoc() {
    final String      scanPath    = aquiver.getBootCls().getPackage().getName() + ".bean";
    final BeanManager beanManager = new BeanManager(scanPath);
    try {
      beanManager.start();
    } catch (IllegalAccessException | InstantiationException e) {
      log.error("An exception occurred while initializing the ioc container", e);
    }
  }

  private void initSSL() throws CertificateException, SSLException {
    log.info("Check if the ssl configuration is enabled.");

    final Boolean               ssl = environment.getBoolean(PATH_SERVER_SSL, SERVER_SSL);
    final SelfSignedCertificate ssc = new SelfSignedCertificate();

    if (ssl) {
      log.info("Ssl configuration takes effect :{}", true);

      final String sslCert           = this.environment.get(PATH_SERVER_SSL_CERT, null);
      final String sslPrivateKey     = this.environment.get(PATH_SERVER_SSL_PRIVATE_KEY, null);
      final String sslPrivateKeyPass = this.environment.get(PATH_SERVER_SSL_PRIVATE_KEY_PASS, null);

      log.info("SSL CertChainFile  Path: {}", sslCert);
      log.info("SSL PrivateKeyFile Path: {}", sslPrivateKey);
      log.info("SSL PrivateKey Pass: {}", sslPrivateKeyPass);

      sslContext = SslContextBuilder.forServer(setKeyCertFileAndPriKey(sslCert, ssc.certificate()),
              setKeyCertFileAndPriKey(sslPrivateKey, ssc.privateKey()), sslPrivateKeyPass).build();
    }

    log.info("Current com.crispy.service ssl startup status: {}", ssl);
    log.info("A valid ssl connection configuration is not configured and is rolled back to the default connection state.");
  }

  /**
   * init webSocket and bind netty childHandler
   */
  private void initWebSocket() {
    final Boolean webSocket = environment.getBoolean(PATH_SERVER_WEBSOCKET, SERVER_WEBSOCKET);
    log.info("Websocket current state: {}", webSocket);

    if (!webSocket) {
      final String webSocketPath = environment.get(PATH_SERVER_WEBSOCKET_PATH, SERVER_WEBSOCKET_PATH);
      this.serverBootstrap.childHandler(new WebSocketServerInitializer(webSocketPath));
      log.info("Websocket path: {}", webSocketPath);
      log.info("Websocket initialization configuration completed");
    }
  }

  /**
   * Open an com.crispy.http server and initialize the Netty configuration.
   * {@link NettyServerInitializer}
   * <p>
   * After initializing, the thread group is initialized according to the configuration parameters, including
   * {@code netty.accept-thread-count} 和 {@code netty.io-thread-count}
   * <p>
   * Then, according to the current system, the choice of communication com.crispy.model for Mac or Windows, NIO mode on Windows.
   * Judgment method in {@link EventLoopKit}, {@code nioGroup} is the judgment method under windows
   * {@code epollGroup} is the judgment method for Mac system or Linux system.
   * <p>
   * After these are done, they will get the port and ip address to start. These are all user-configurable.
   *
   * @param startTime
   * @throws Exception
   */
  private void startServer(long startTime) throws Exception {

    ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.DISABLED);

    this.serverBootstrap.childHandler(new NettyServerInitializer(sslContext, environment));

    int acceptThreadCount = environment.getInteger(PATH_SERVER_NETTY_ACCEPT_THREAD_COUNT, DEFAULT_ACCEPT_THREAD_COUNT);
    int ioThreadCount     = environment.getInteger(PATH_SERVER_NETTY_IO_THREAD_COUNT, DEFAULT_IO_THREAD_COUNT);

    NettyServerGroup nettyServerGroup = EventLoopKit.nioGroup(acceptThreadCount, ioThreadCount);
    this.bossGroup   = nettyServerGroup.getBossGroup();
    this.workerGroup = nettyServerGroup.getWorkGroup();

    if (EventLoopKit.epollIsAvailable()) {
      nettyServerGroup = EventLoopKit.epollGroup(acceptThreadCount, ioThreadCount);
      this.bossGroup   = nettyServerGroup.getBossGroup();
      this.workerGroup = nettyServerGroup.getWorkGroup();
    }

    this.serverBootstrap.group(bossGroup, workerGroup).channel(nettyServerGroup.getChannelClass());

    log.info("The IO mode of the application startup is: {}", EventLoopKit.judgeMode(nettyServerGroup.getChannelClass().getSimpleName()));

    this.stop = false;

    final Integer port    = this.environment.getInteger(PATH_SERVER_PORT, SERVER_PORT);
    final String  address = this.environment.get(PATH_SERVER_ADDRESS, SERVER_ADDRESS);
    this.channel = serverBootstrap.bind(address, port).sync().channel();

    long endTime      = System.currentTimeMillis();
    long startUpTime  = (endTime - startTime);
    long jvmStartTime = (endTime - Systems.getJvmStartUpTime());

    log.info("Crispy isStop on port(s): {} (com.crispy.http) with context path ''", port);
    log.info("Started {} in {} ms (JVM running for {} ms)", aquiver.bootClsName(), startUpTime, jvmStartTime);
  }

  /**
   * stop http server
   */
  @Override
  public void stop() {
    log.info("Netty Server Shutdown...");
    if (stop) {
      return;
    }
    stop = true;
    try {
      if (bossGroup != null) {
        this.bossGroup.shutdownGracefully();
      }
      if (workerGroup != null) {
        this.workerGroup.shutdownGracefully();
      }
      log.info("The netty service is gracefully closed");
    } catch (Exception e) {
      log.error("An exception occurred while the Netty Http service was down", e);
    }
  }

  @Override
  public void join() {
    try {
      this.channel.closeFuture().sync();
    } catch (InterruptedException e) {
      log.error("Channel close future fail", e);
    }
  }

  /**
   * Observe the environment class, which can be opened by configuration.
   * After opening, it will perform various operations through the log
   * printing environment.
   */
  private void watchEnv() {
    boolean watch = this.environment.getBoolean(PATH_ENV_WATCHER, false);
    if (watch) {
      log.info("start application watcher");
      final GlobalEnvListener fileListener = new GlobalEnvListener();
      GlobalEnvObserver.config().watchPath(SERVER_WATCHER_PATH).listener(fileListener).start();
    }
  }

  /**
   * Add a hook that stops the current service when the system is shut down
   */
  private void shutdownHook() {
    Thread shutdownThread = new Thread(this::stop);
    shutdownThread.setName("shutdown@thread");
    Runtime.getRuntime().addShutdownHook(shutdownThread);
  }

  /**
   * print default banner
   */
  private void printBanner() {
    this.defaultBanner.printBanner(System.out, aquiver.bannerText(), aquiver.bannerFont());
  }

  /**
   * Use the configured path if the certificate and private key are
   * configured, otherwise use the default configuration
   *
   * @param keyPath
   * @param defaultFilePath
   * @return
   */
  private File setKeyCertFileAndPriKey(String keyPath, File defaultFilePath) {
    return keyPath != null ? new File(keyPath) : defaultFilePath;
  }
}