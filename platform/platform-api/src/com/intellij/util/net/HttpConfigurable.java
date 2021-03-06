/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.util.net;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.components.*;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.util.PopupUtil;
import com.intellij.openapi.util.*;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.CharsetToolkit;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.openapi.wm.IdeFrame;
import com.intellij.util.Base64;
import com.intellij.util.SystemProperties;
import com.intellij.util.WaitForProgressToShow;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.proxy.CommonProxy;
import com.intellij.util.proxy.JavaProxyProperty;
import com.intellij.util.xmlb.XmlSerializer;
import com.intellij.util.xmlb.XmlSerializerUtil;
import com.intellij.util.xmlb.annotations.Transient;
import gnu.trove.THashMap;
import gnu.trove.THashSet;
import gnu.trove.TObjectObjectProcedure;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.NTCredentials;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.AuthSchemes;
import org.apache.http.client.config.RequestConfig;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.io.IOException;
import java.net.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

@State(
  name = "HttpConfigurable",
  storages = {
    @Storage(file = StoragePathMacros.APP_CONFIG + "/proxy.settings.xml"),
    // we use two storages due to backward compatibility, see http://crucible.labs.intellij.net/cru/CR-IC-5142
    @Storage(file = StoragePathMacros.APP_CONFIG + "/other.xml", deprecated = true)
  }
)
public class HttpConfigurable implements PersistentStateComponent<HttpConfigurable>, ApplicationComponent {
  public static final int CONNECTION_TIMEOUT = SystemProperties.getIntProperty("idea.connection.timeout", 10000);
  public static final int READ_TIMEOUT = SystemProperties.getIntProperty("idea.read.timeout", 60000);
  public static final int REDIRECT_LIMIT = SystemProperties.getIntProperty("idea.redirect.limit", 10);

  public boolean PROXY_TYPE_IS_SOCKS;
  public boolean USE_HTTP_PROXY;
  public boolean USE_PROXY_PAC;
  public volatile transient boolean AUTHENTICATION_CANCELLED;
  public String PROXY_HOST;
  public int PROXY_PORT = 80;

  public volatile boolean PROXY_AUTHENTICATION;
  public volatile String PROXY_LOGIN;
  public volatile String PROXY_PASSWORD_CRYPT;
  public boolean KEEP_PROXY_PASSWORD;
  public transient String LAST_ERROR;

  private final THashMap<CommonProxy.HostInfo, ProxyInfo> myGenericPasswords = new THashMap<CommonProxy.HostInfo, ProxyInfo>();
  private final Set<CommonProxy.HostInfo> myGenericCancelled = new THashSet<CommonProxy.HostInfo>();

  public String PROXY_EXCEPTIONS;
  public boolean USE_PAC_URL;
  public String PAC_URL;

  private transient IdeaWideProxySelector mySelector;

  private transient final Object myLock = new Object();

  @SuppressWarnings("UnusedDeclaration")
  public transient Getter<PasswordAuthentication> myTestAuthRunnable = new StaticGetter<PasswordAuthentication>(null);
  public transient Getter<PasswordAuthentication> myTestGenericAuthRunnable = new StaticGetter<PasswordAuthentication>(null);

  public static HttpConfigurable getInstance() {
    return ApplicationManager.getApplication().getComponent(HttpConfigurable.class);
  }

  public static boolean editConfigurable(@Nullable JComponent parent) {
    return ShowSettingsUtil.getInstance().editConfigurable(parent, new HttpProxyConfigurable());
  }

  @Override
  public HttpConfigurable getState() {
    CommonProxy.isInstalledAssertion();

    HttpConfigurable state = new HttpConfigurable();
    XmlSerializerUtil.copyBean(this, state);
    if (!KEEP_PROXY_PASSWORD) {
      state.PROXY_PASSWORD_CRYPT = null;
    }
    correctPasswords(state);
    return state;
  }

  @Override
  public void initComponent() {
    mySelector = new IdeaWideProxySelector(this);
    String name = getClass().getName();
    CommonProxy.getInstance().setCustom(name, mySelector);
    CommonProxy.getInstance().setCustomAuth(name, new IdeaWideAuthenticator(this));
  }

  @NotNull
  public ProxySelector getOnlyBySettingsSelector() {
    return mySelector;
  }

  @Override
  public void disposeComponent() {
    final String name = getClass().getName();
    CommonProxy.getInstance().removeCustom(name);
    CommonProxy.getInstance().removeCustomAuth(name);
  }

  @NotNull
  @Override
  public String getComponentName() {
    return getClass().getName();
  }

  private void correctPasswords(@NotNull HttpConfigurable to) {
    synchronized (myLock) {
      to.myGenericPasswords.retainEntries(new TObjectObjectProcedure<CommonProxy.HostInfo, ProxyInfo>() {
        @Override
        public boolean execute(CommonProxy.HostInfo hostInfo, ProxyInfo proxyInfo) {
          return proxyInfo.isStore();
        }
      });
    }
  }

  @Override
  public void loadState(@NotNull HttpConfigurable state) {
    XmlSerializerUtil.copyBean(state, this);
    if (!KEEP_PROXY_PASSWORD) {
      PROXY_PASSWORD_CRYPT = null;
    }
    correctPasswords(this);
  }

  public boolean isGenericPasswordCanceled(@NotNull String host, int port) {
    synchronized (myLock) {
      return myGenericCancelled.contains(new CommonProxy.HostInfo(null, host, port));
    }
  }

  public void setGenericPasswordCanceled(final String host, final int port) {
    synchronized (myLock) {
      myGenericCancelled.add(new CommonProxy.HostInfo(null, host, port));
    }
  }

  public PasswordAuthentication getGenericPassword(@NotNull String host, int port) {
    final ProxyInfo proxyInfo;
    synchronized (myLock) {
      proxyInfo = myGenericPasswords.get(new CommonProxy.HostInfo(null, host, port));
    }
    if (proxyInfo == null) {
      return null;
    }
    return new PasswordAuthentication(proxyInfo.getUsername(), decode(String.valueOf(proxyInfo.getPasswordCrypt())).toCharArray());
  }

  public void putGenericPassword(final String host, final int port, @NotNull PasswordAuthentication authentication, boolean remember) {
    PasswordAuthentication coded = new PasswordAuthentication(authentication.getUserName(), encode(String.valueOf(authentication.getPassword())).toCharArray());
    synchronized (myLock) {
      myGenericPasswords.put(new CommonProxy.HostInfo(null, host, port), new ProxyInfo(remember, coded.getUserName(), String.valueOf(coded.getPassword())));
    }
  }

  @Transient
  @Nullable
  public String getPlainProxyPassword() {
    return PROXY_PASSWORD_CRYPT == null ? null : decode(PROXY_PASSWORD_CRYPT);
  }

  private static String decode(String value) {
    return new String(Base64.decode(value));
  }

  @Transient
  public void setPlainProxyPassword (String password) {
    PROXY_PASSWORD_CRYPT = encode(password);
  }

  private static String encode(String password) {
    return new String(Base64.encode(password.getBytes(CharsetToolkit.UTF8_CHARSET)));
  }

  public PasswordAuthentication getGenericPromptedAuthentication(final String prefix, final String host, final String prompt, final int port, final boolean remember) {
    if (ApplicationManager.getApplication().isUnitTestMode()) {
      return myTestGenericAuthRunnable.get();
    }

    final Ref<PasswordAuthentication> value = Ref.create();
    runAboveAll(new Runnable() {
      @Override
      public void run() {
        if (isGenericPasswordCanceled(host, port)) {
          return;
        }

        PasswordAuthentication password = getGenericPassword(host, port);
        if (password != null) {
          value.set(password);
          return;
        }

        AuthenticationDialog dialog = new AuthenticationDialog(PopupUtil.getActiveComponent(), prefix + host,
                                                               "Please enter credentials for: " + prompt, "", "", remember);
        dialog.show();
        if (dialog.getExitCode() == DialogWrapper.OK_EXIT_CODE) {
          AuthenticationPanel panel = dialog.getPanel();
          PasswordAuthentication passwordAuthentication = new PasswordAuthentication(panel.getLogin(), panel.getPassword());
          putGenericPassword(host, port, passwordAuthentication, remember && panel.isRememberPassword());
          value.set(passwordAuthentication);
        }
        else {
          setGenericPasswordCanceled(host, port);
        }
      }
    });
    return value.get();
  }

  public PasswordAuthentication getPromptedAuthentication(final String host, final String prompt) {
    if (AUTHENTICATION_CANCELLED) {
      return null;
    }
    final String password = getPlainProxyPassword();
    if (PROXY_AUTHENTICATION && ! StringUtil.isEmptyOrSpaces(PROXY_LOGIN) && ! StringUtil.isEmptyOrSpaces(password)) {
      return new PasswordAuthentication(PROXY_LOGIN, password.toCharArray());
    }

    // do not try to show any dialogs if application is exiting
    if (ApplicationManager.getApplication() == null || ApplicationManager.getApplication().isDisposeInProgress() ||
        ApplicationManager.getApplication().isDisposed()) return null;

    if (ApplicationManager.getApplication().isUnitTestMode()) {
      return myTestGenericAuthRunnable.get();
    }
    final PasswordAuthentication[] value = new PasswordAuthentication[1];
    runAboveAll(new Runnable() {
      @Override
      public void run() {
        if (AUTHENTICATION_CANCELLED) {
          return;
        }

        // password might have changed, and the check below is for that
        String password = getPlainProxyPassword();
        if (PROXY_AUTHENTICATION && ! StringUtil.isEmptyOrSpaces(PROXY_LOGIN) && ! StringUtil.isEmptyOrSpaces(password)) {
          value[0] = new PasswordAuthentication(PROXY_LOGIN, password.toCharArray());
          return;
        }
        AuthenticationDialog dialog = new AuthenticationDialog(PopupUtil.getActiveComponent(), "Proxy authentication: " + host,
                                                                  "Please enter credentials for: " + prompt, PROXY_LOGIN, "", KEEP_PROXY_PASSWORD);
        dialog.show();
        if (dialog.getExitCode() == DialogWrapper.OK_EXIT_CODE) {
          PROXY_AUTHENTICATION = true;
          AuthenticationPanel panel = dialog.getPanel();
          KEEP_PROXY_PASSWORD = panel.isRememberPassword();
          PROXY_LOGIN = StringUtil.nullize(panel.getLogin());
          setPlainProxyPassword(String.valueOf(panel.getPassword()));
          value[0] = new PasswordAuthentication(panel.getLogin(), panel.getPassword());
        } else {
          AUTHENTICATION_CANCELLED = true;
        }
      }
    });
    return value[0];
  }

  private static void runAboveAll(@NotNull final Runnable runnable) {
    ProgressIndicator progressIndicator = ProgressManager.getInstance().getProgressIndicator();
    if (progressIndicator != null && progressIndicator.isModal()) {
      WaitForProgressToShow.runOrInvokeAndWaitAboveProgress(runnable);
    }
    else {
      Application app = ApplicationManager.getApplication();
      if (app.isDispatchThread()) {
        runnable.run();
      }
      else {
        app.invokeAndWait(runnable, ModalityState.any());
      }
    }
  }

  //these methods are preserved for compatibility with com.intellij.openapi.project.impl.IdeaServerSettings
  @Deprecated
  public void readExternal(Element element) throws InvalidDataException {
    //noinspection ConstantConditions
    loadState(XmlSerializer.deserialize(element, HttpConfigurable.class));
  }

  @Deprecated
  public void writeExternal(Element element) throws WriteExternalException {
    XmlSerializer.serializeInto(getState(), element);
    if (USE_PROXY_PAC && USE_HTTP_PROXY && !ApplicationManager.getApplication().isDisposed()) {
      ApplicationManager.getApplication().invokeLater(new Runnable() {
        @Override
        public void run() {
          IdeFrame frame = IdeFocusManager.findInstance().getLastFocusedFrame();
          if (frame != null) {
            USE_PROXY_PAC = false;
            Messages.showMessageDialog(frame.getComponent(), "Proxy: both 'use proxy' and 'autodetect proxy' settings were set." +
                                                             "\nOnly one of these options should be selected.\nPlease re-configure.",
                                       "Proxy Setup", Messages.getWarningIcon());
            editConfigurable(frame.getComponent());
          }
        }
      }, ModalityState.NON_MODAL);
    }
  }

  /**
   * todo [all] It is NOT necessary to call anything if you obey common IDEA proxy settings;
   * todo if you want to define your own behaviour, refer to {@link com.intellij.util.proxy.CommonProxy}
   *
   * also, this method is useful in a way that it test connection to the host [through proxy]
   *
   * @param url URL for HTTP connection
   * @throws IOException
   */
  public void prepareURL(@NotNull String url) throws IOException {
    URLConnection connection = openConnection(url);
    try {
      connection.connect();
      connection.getInputStream();
    }
    catch (IOException e) {
      throw e;
    }
    catch (Throwable ignored) {
    }
    finally {
      if (connection instanceof HttpURLConnection) {
        ((HttpURLConnection)connection).disconnect();
      }
    }
  }

  @NotNull
  public URLConnection openConnection(@NotNull String location) throws IOException {
    final URL url = new URL(location);
    URLConnection urlConnection = null;
    final List<Proxy> proxies = CommonProxy.getInstance().select(url);
    if (ContainerUtil.isEmpty(proxies)) {
      urlConnection = url.openConnection();
    }
    else {
      IOException exception = null;
      for (Proxy proxy : proxies) {
        try {
          urlConnection = url.openConnection(proxy);
        }
        catch (IOException e) {
          // continue iteration
          exception = e;
        }
      }
      if (urlConnection == null && exception != null) {
        throw exception;
      }
    }

    assert urlConnection != null;
    urlConnection.setReadTimeout(READ_TIMEOUT);
    urlConnection.setConnectTimeout(CONNECTION_TIMEOUT);
    return urlConnection;
  }

  /**
   * Opens HTTP connection to a given location using configured http proxy settings.
   * @param location url to connect to
   * @return instance of {@link HttpURLConnection}
   * @throws IOException in case of any I/O troubles or if created connection isn't instance of HttpURLConnection.
   */
  @NotNull
  public HttpURLConnection openHttpConnection(@NotNull String location) throws IOException {
    URLConnection urlConnection = openConnection(location);
    if (urlConnection instanceof HttpURLConnection) {
      return (HttpURLConnection) urlConnection;
    }
    else {
      throw new IOException("Expected " + HttpURLConnection.class + ", but got " + urlConnection.getClass());
    }
  }

  public boolean isHttpProxyEnabledForUrl(@Nullable String url) {
    if (!USE_HTTP_PROXY) return false;
    URI uri = url != null ? VfsUtil.toUri(url) : null;
    return uri == null || !mySelector.isProxyException(uri.getHost());
  }

  /**
   * @deprecated To be removed in IDEA 16. Use corresponding method of IdeHttpClientHelpers.
   */
  @Deprecated
  @NotNull
  public RequestConfig.Builder setProxy(@NotNull RequestConfig.Builder builder) {
    if (USE_HTTP_PROXY) {
      builder.setProxy(new HttpHost(PROXY_HOST, PROXY_PORT));
    }
    return builder;
  }

  /**
   * @deprecated To be removed in IDEA 16. Use corresponding method of IdeHttpClientHelpers.
   */
  @Deprecated
  @NotNull
  public CredentialsProvider setProxyCredentials(@NotNull CredentialsProvider provider) {
    if (USE_HTTP_PROXY && PROXY_AUTHENTICATION) {
      String ntlmUserPassword = PROXY_LOGIN.replace('\\', '/') + ":" + getPlainProxyPassword();
      provider.setCredentials(new AuthScope(PROXY_HOST, PROXY_PORT, AuthScope.ANY_REALM, AuthSchemes.NTLM), new NTCredentials(ntlmUserPassword));
      provider.setCredentials(new AuthScope(PROXY_HOST, PROXY_PORT), new UsernamePasswordCredentials(PROXY_LOGIN, getPlainProxyPassword()));
    }
    return provider;
  }

  /**
   * @deprecated To be removed in IDEA 15. This method was not supposed to be here. Use corresponding methods of IdeHttpClientHelpers.
   */
  @Deprecated
  @NotNull
  public RequestConfig.Builder setProxy(@NotNull RequestConfig.Builder builder, boolean useProxy) {
    if (useProxy) setProxy(builder);
    return builder;
  }

  /**
   * @deprecated To be removed in IDEA 15. This method was not supposed to be here. Use corresponding methods of IdeHttpClientHelpers.
   */
  @Deprecated
  @NotNull
  public CredentialsProvider setProxyCredentials(@NotNull CredentialsProvider provider, boolean useProxy) {
    if (useProxy) setProxyCredentials(provider);
    return provider;
  }

  public static List<KeyValue<String, String>> getJvmPropertiesList(final boolean withAutodetection, @Nullable final URI uri) {
    final HttpConfigurable me = getInstance();
    if (! me.USE_HTTP_PROXY && ! me.USE_PROXY_PAC) {
      return Collections.emptyList();
    }
    final List<KeyValue<String, String>> result = new ArrayList<KeyValue<String, String>>();
    if (me.USE_HTTP_PROXY) {
      final boolean putCredentials = me.KEEP_PROXY_PASSWORD && StringUtil.isNotEmpty(me.PROXY_LOGIN);
      if (me.PROXY_TYPE_IS_SOCKS) {
        result.add(KeyValue.create(JavaProxyProperty.SOCKS_HOST, me.PROXY_HOST));
        result.add(KeyValue.create(JavaProxyProperty.SOCKS_PORT, String.valueOf(me.PROXY_PORT)));
        if (putCredentials) {
          result.add(KeyValue.create(JavaProxyProperty.SOCKS_USERNAME, me.PROXY_LOGIN));
          result.add(KeyValue.create(JavaProxyProperty.SOCKS_PASSWORD, me.getPlainProxyPassword()));
        }
      } else {
        result.add(KeyValue.create(JavaProxyProperty.HTTP_HOST, me.PROXY_HOST));
        result.add(KeyValue.create(JavaProxyProperty.HTTP_PORT, String.valueOf(me.PROXY_PORT)));
        result.add(KeyValue.create(JavaProxyProperty.HTTPS_HOST, me.PROXY_HOST));
        result.add(KeyValue.create(JavaProxyProperty.HTTPS_PORT, String.valueOf(me.PROXY_PORT)));
        if (putCredentials) {
          result.add(KeyValue.create(JavaProxyProperty.HTTP_USERNAME, me.PROXY_LOGIN));
          result.add(KeyValue.create(JavaProxyProperty.HTTP_PASSWORD, me.getPlainProxyPassword()));
        }
      }
    } else if (me.USE_PROXY_PAC && withAutodetection && uri != null) {
      final List<Proxy> proxies = CommonProxy.getInstance().select(uri);
      // we will just take the first returned proxy, but we have an option to test connection through each of them,
      // for instance, by calling prepareUrl()
      if (proxies != null && ! proxies.isEmpty()) {
        for (Proxy proxy : proxies) {
          if (isRealProxy(proxy)) {
            final SocketAddress address = proxy.address();
            if (address instanceof InetSocketAddress) {
              final InetSocketAddress inetSocketAddress = (InetSocketAddress)address;
              if (Proxy.Type.SOCKS.equals(proxy.type())) {
                result.add(KeyValue.create(JavaProxyProperty.SOCKS_HOST, inetSocketAddress.getHostName()));
                result.add(KeyValue.create(JavaProxyProperty.SOCKS_PORT, String.valueOf(inetSocketAddress.getPort())));
              } else {
                result.add(KeyValue.create(JavaProxyProperty.HTTP_HOST, inetSocketAddress.getHostName()));
                result.add(KeyValue.create(JavaProxyProperty.HTTP_PORT, String.valueOf(inetSocketAddress.getPort())));
                result.add(KeyValue.create(JavaProxyProperty.HTTPS_HOST, inetSocketAddress.getHostName()));
                result.add(KeyValue.create(JavaProxyProperty.HTTPS_PORT, String.valueOf(inetSocketAddress.getPort())));
              }
            }
          }
        }
      }
    }
    return result;
  }

  public static boolean isRealProxy(@NotNull Proxy proxy) {
    return !Proxy.NO_PROXY.equals(proxy) && !Proxy.Type.DIRECT.equals(proxy.type());
  }

  @NotNull
  public static List<String> convertArguments(@NotNull final List<KeyValue<String, String>> list) {
    if (list.isEmpty()) {
      return Collections.emptyList();
    }
    final List<String> result = new ArrayList<String>(list.size());
    for (KeyValue<String, String> value : list) {
      result.add("-D" + value.getKey() + "=" + value.getValue());
    }
    return result;
  }

  public void clearGenericPasswords() {
    synchronized (myLock) {
      myGenericPasswords.clear();
      myGenericCancelled.clear();
    }
  }

  public void removeGeneric(@NotNull CommonProxy.HostInfo info) {
    synchronized (myLock) {
      myGenericPasswords.remove(info);
    }
  }

  public static class ProxyInfo {
    public boolean myStore;
    public String myUsername;
    public String myPasswordCrypt;

    @SuppressWarnings("UnusedDeclaration")
    public ProxyInfo() {
    }

    public ProxyInfo(boolean store, String username, String passwordCrypt) {
      myStore = store;
      myUsername = username;
      myPasswordCrypt = passwordCrypt;
    }

    public boolean isStore() {
      return myStore;
    }

    public void setStore(boolean store) {
      myStore = store;
    }

    public String getUsername() {
      return myUsername;
    }

    public void setUsername(String username) {
      myUsername = username;
    }

    public String getPasswordCrypt() {
      return myPasswordCrypt;
    }

    @SuppressWarnings("UnusedDeclaration")
    public void setPasswordCrypt(String passwordCrypt) {
      myPasswordCrypt = passwordCrypt;
    }
  }
}
