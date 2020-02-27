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
package org.aquiver;

import org.aquiver.server.Server;
import org.aquiver.server.banner.BannerFont;
import org.aquiver.server.NettyServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.StringReader;
import java.net.BindException;
import java.util.*;
import java.util.concurrent.CountDownLatch;

import static org.aquiver.Const.*;

/**
 * @author WangYi
 * @since 2019/6/5
 */
public final class Aquiver {

  private static final Logger log = LoggerFactory.getLogger(Aquiver.class);

  private Server      nettyServer = new NettyServer();
  private Environment environment = Environment.of();
  private Set<String>    packages       = new LinkedHashSet<>();
  private List<Class<?>> eventPool      = new LinkedList<>();
  private CountDownLatch countDownLatch = new CountDownLatch(1);
  private String         bootConfName   = PATH_CONFIG_PROPERTIES;
  private String         envName        = "default";
  private boolean        envConfig      = false;
  private boolean        masterConfig   = false;
  private boolean        started        = false;
  private int            port;
  private Class<?>       bootCls;
  private String         bannerText;
  private String         bannerFont;

  private Aquiver() {
  }

  public static Aquiver of() {
    return new Aquiver();
  }

  public Environment environment() {
    return environment;
  }

  public void environment(Environment environment) {
    this.environment = environment;
  }

  public Class<?> getBootCls() {
    return bootCls;
  }

  public String bootClsName() {
    return bootCls.getSimpleName();
  }

  public Aquiver bind(int port) {
    this.port = port;
    this.environment.add(PATH_SERVER_PORT, port);
    return this;
  }

  public int port() {
    return port;
  }

  public boolean verbose() {
    return this.environment.getBoolean(PATH_SCAN_VERBOSE, false);
  }

  public void verbose(boolean verbose) {
    this.environment.add(PATH_SCAN_VERBOSE, verbose);
  }

  public boolean enableRealtimeLogging() {
    return this.environment.getBoolean(PATH_SCAN_LOGGING, false);
  }

  public void enableRealtimeLogging(boolean enableRealtimeLogging) {
    this.environment.add(PATH_SCAN_LOGGING, enableRealtimeLogging);
  }

  public String bannerText() {
    if (bannerText == null) {
      return this.environment.getString(PATH_APP_BANNER_TEXT, BANNER_TEXT);
    }
    return bannerText;
  }

  public void bannerText(String bannerText) {
    this.bannerText = bannerText;
    this.environment.add(PATH_APP_BANNER_TEXT, bannerText);
  }

  public String bannerFont() {
    if (bannerFont == null) {
      return this.environment.getString(PATH_APP_BANNER_FONT, BannerFont.FONT_DEFAULT);
    }
    return bannerFont;
  }

  public void bannerFont(String bannerFont) {
    this.bannerFont = bannerFont;
    this.environment.add(PATH_APP_BANNER_FONT, bannerFont);
  }

  public boolean envConfig() {
    return envConfig;
  }

  public boolean masterConfig() {
    return masterConfig;
  }

  public String bootConfName() {
    return bootConfName;
  }

  public String envName() {
    return envName;
  }

  public Set<String> packages() {
    return packages;
  }

  public List<Class<?>> eventPool() {
    return eventPool;
  }

  public void start(Class<?> bootClass, String[] args) {
    try {
      this.loadConfig(args);
    } catch (IllegalAccessException e) {
      log.error("An exception occurred while loading the configuration", e);
    }
    Thread bootThread = new Thread(() -> {
      try {
        this.bootCls = bootClass;
        this.nettyServer.start(this);
        this.countDownLatch.countDown();
        this.nettyServer.join();
      } catch (BindException e) {
        log.error("Bind port is exception:", e);
      } catch (Exception e) {
        log.error("An exception occurred while the com.crispy.service started", e);
      }
    });

    String threadName = this.environment.get(PATH_APP_THREAD_NAME, SERVER_THREAD_NAME);

    bootThread.setName(threadName);
    bootThread.start();
    this.started = true;
  }

  /**
   * Load configuration from multiple places between startup services. Support items are:
   * 1. Properties are configured by default, and the properties loaded by default are application.properties
   * 2. If there is no properties configuration, the yaml format is used, and the default yaml loaded is application.yml
   * 3. Support loading configuration from args array of main function
   * 4. Support loading configuration from System.Property
   */
  private void loadConfig(String[] args) throws IllegalAccessException {
    String              bootConf    = environment().get(PATH_SERVER_BOOT_CONFIG, PATH_CONFIG_PROPERTIES);
    Environment         bootConfEnv = Environment.of(bootConf);
    Map<String, String> argsMap     = this.loadMainArgs(args);
    this.loadSystemProperty(bootConfEnv);
    this.loadYamlConfig(bootConfEnv);
    this.loadEnvConfig(argsMap);
  }

  /**
   * According to whether the configuration information of the specified
   * environment exists in the main function, and then perform configuration
   * loading according to this configuration information
   *
   * @param argsMap
   */
  private void loadEnvConfig(Map<String, String> argsMap) {
    if (argsMap.get(PATH_SERVER_PROFILE) != null) {
      String envNameArg = argsMap.get(PATH_SERVER_PROFILE);
      this.envConfig(envNameArg);
      this.envName = envNameArg;
      argsMap.remove(PATH_SERVER_PROFILE);
      this.envConfig = true;
    }

    if (!envConfig) {
      String profileName = this.environment.get(PATH_SERVER_PROFILE);
      if (profileName != null && !profileName.equals("")) {
        envConfig(profileName);
        this.envName = profileName;
      }
    }
  }

  /**
   * Load yaml configuration file to support yaml format syntax
   * configuration, defaults to properties
   *
   * @param bootConfEnv
   */
  private void loadYamlConfig(Environment bootConfEnv) {
    if (bootConfEnv.isEmpty()) {
      TreeMap<String, Map<String, Object>> yamlConfigTreeMap = Propertys.yaml(PATH_CONFIG_YAML);
      if (yamlConfigTreeMap != null) {
        bootConfEnv.load(new StringReader(Propertys.toProperties(yamlConfigTreeMap)));
        this.bootConfName = PATH_CONFIG_YAML;
      }
    }
    if (!Objects.requireNonNull(bootConfEnv).isEmpty()) {
      Map<String, String>            bootEnvMap = bootConfEnv.toStringMap();
      Set<Map.Entry<String, String>> entrySet   = bootEnvMap.entrySet();
      entrySet.forEach(entry -> this.environment.add(entry.getKey(), entry.getValue()));
      this.masterConfig = true;
    }
  }

  /**
   * Load the system property parameter and overwrite if the main configuration exists
   *
   * @param bootConfEnv
   * @throws IllegalAccessException
   */
  private void loadSystemProperty(Environment bootConfEnv) throws IllegalAccessException {
    Map<String, String> constField = Propertys.confFieldMap();
    for (String key : constField.keySet()) {
      final String property = System.getProperty(constField.get(key));
      if (property != null) {
        bootConfEnv.add(key, property);
      }
    }
  }

  /**
   * Load main function parameters, and override if main configuration exists
   *
   * @param args
   * @return
   */
  private Map<String, String> loadMainArgs(String[] args) {
    Map<String, String> argsMap = Propertys.parseArgs(args);
    if (argsMap.size() > 0) {
      log.info("Entered command line:{}", argsMap.toString());
    }

    for (Map.Entry<String, String> next : argsMap.entrySet()) {
      this.environment.add(next.getKey(), next.getValue());
    }
    return argsMap;
  }

  /**
   * Load the environment configuration, if it exists in the main
   * configuration, it will be overwritten in the environment
   * configuration
   *
   * @param envName
   */
  private void envConfig(String envName) {
    String      envFileName = "application" + "-" + envName + ".properties";
    Environment customerEnv = Environment.of(envFileName);
    if (customerEnv.isEmpty()) {
      String envYmlFileName = "application" + "-" + envName + ".yml";
      customerEnv = Environment.of(envYmlFileName);
    }
    if (!customerEnv.isEmpty()) {
      customerEnv.props().forEach((key, value) -> this.environment.add(key.toString(), value));
    }
    this.environment.add(PATH_SERVER_PROFILE, envName);
  }

  /**
   * Await web server started
   *
   * @return return Crispy instance
   */
  public Aquiver await() {
    if (!this.started) {
      throw new IllegalStateException("Server hasn't been started. Call start() before calling this method.");
    }
    try {
      this.countDownLatch.await();
    } catch (Exception e) {
      log.error("Server start await error", e);
      Thread.currentThread().interrupt();
    }
    return this;
  }

  /**
   * Stop web server
   */
  public void stop() {
    this.nettyServer.stop();
  }
}