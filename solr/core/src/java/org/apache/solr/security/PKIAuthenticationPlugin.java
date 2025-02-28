/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.security;

import javax.servlet.FilterChain;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;
import java.security.Principal;
import java.security.PublicKey;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.http.HttpEntity;
import org.apache.http.HttpException;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.HttpResponse;
import org.apache.http.auth.BasicUserPrincipal;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.apache.solr.client.solrj.impl.HttpClientUtil;
import org.apache.solr.client.solrj.impl.HttpListenerFactory;
import org.apache.solr.client.solrj.impl.SolrHttpClientBuilder;
import org.apache.solr.common.util.Base64;
import org.apache.solr.common.util.ExecutorUtil;
import org.apache.solr.common.util.StrUtils;
import org.apache.solr.common.util.SuppressForbidden;
import org.apache.solr.common.util.Utils;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.request.SolrRequestInfo;
import org.apache.solr.util.CryptoKeys;
import org.eclipse.jetty.client.api.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.nio.charset.StandardCharsets.UTF_8;

public class PKIAuthenticationPlugin extends AuthenticationPlugin implements HttpClientBuilderPlugin {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private final Map<String, PublicKey> keyCache = new ConcurrentHashMap<>();
  private final PublicKeyHandler publicKeyHandler;
  private final CoreContainer cores;
  private final int MAX_VALIDITY = Integer.parseInt(System.getProperty("pkiauth.ttl", "15000"));
  private final String myNodeName;
  private final HttpHeaderClientInterceptor interceptor = new HttpHeaderClientInterceptor();
  private boolean interceptorRegistered = false;

  public boolean isInterceptorRegistered(){
    return interceptorRegistered;
  }

  public PKIAuthenticationPlugin(CoreContainer cores, String nodeName, PublicKeyHandler publicKeyHandler) {
    this.publicKeyHandler = publicKeyHandler;
    this.cores = cores;
    myNodeName = nodeName;
  }

  @Override
  public void init(Map<String, Object> pluginConfig) {
  }


  @SuppressForbidden(reason = "Needs currentTimeMillis to compare against time in header")
  @Override
  public boolean doAuthenticate(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws Exception {
    // Getting the received time must be the first thing we do, processing the request can take time
    long receivedTime = System.currentTimeMillis();

    String requestURI = request.getRequestURI();
    if (requestURI.endsWith(PublicKeyHandler.PATH)) {
      assert false : "Should already be handled by SolrDispatchFilter.authenticateRequest";

      numPassThrough.inc();
      filterChain.doFilter(request, response);
      return true;
    }

    String header = request.getHeader(HEADER);
    assert header != null : "Should have been checked by SolrDispatchFilter.authenticateRequest";

    List<String> authInfo = StrUtils.splitWS(header, false);
    if (authInfo.size() != 2) {
      numErrors.mark();
      log.error("Invalid SolrAuth header: {}", header);
      response.setHeader(HttpHeaders.WWW_AUTHENTICATE, HEADER);
      response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid SolrAuth header");
      return false;
    }

    String nodeName = authInfo.get(0);
    String cipher = authInfo.get(1);

    PKIHeaderData decipher = decipherHeader(nodeName, cipher);
    if (decipher == null) {
      numMissingCredentials.inc();
      log.error("Could not load principal from SolrAuth header.");
      response.setHeader(HttpHeaders.WWW_AUTHENTICATE, HEADER);
      response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Could not load principal from SolrAuth header.");
      return false;
    }
    long elapsed = receivedTime - decipher.timestamp;
    if (elapsed > MAX_VALIDITY) {
      numErrors.mark();
      log.error("Expired key request timestamp, elapsed={}, TTL={}", elapsed, MAX_VALIDITY);
      response.setHeader(HttpHeaders.WWW_AUTHENTICATE, HEADER);
      response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Expired key request timestamp");
      return false;
    }

    final Principal principal = "$".equals(decipher.userName) ?
        SU :
        new BasicUserPrincipal(decipher.userName);

    numAuthenticated.inc();
    filterChain.doFilter(wrapWithPrincipal(request, principal), response);
    return true;
  }

  public static class PKIHeaderData {
    String userName;
    long timestamp;
  }

  private PKIHeaderData decipherHeader(String nodeName, String cipherBase64) {
    PublicKey key = keyCache.get(nodeName);
    if (key == null) {
      log.debug("No key available for node : {} fetching now ", nodeName);
      key = getRemotePublicKey(nodeName);
      log.debug("public key obtained {} ", key);
    }

    PKIHeaderData header = parseCipher(cipherBase64, key);
    if (header == null) {
      log.warn("Failed to decrypt header, trying after refreshing the key ");
      key = getRemotePublicKey(nodeName);
      return parseCipher(cipherBase64, key);
    } else {
      return header;
    }
  }

  private static PKIHeaderData parseCipher(String cipher, PublicKey key) {
    byte[] bytes;
    try {
      bytes = CryptoKeys.decryptRSA(Base64.base64ToByteArray(cipher), key);
    } catch (Exception e) {
      log.error("Decryption failed , key must be wrong", e);
      return null;
    }
    String s = new String(bytes, UTF_8).trim();
    int splitPoint = s.lastIndexOf(' ');
    if (splitPoint == -1) {
      log.warn("Invalid cipher {} deciphered data {}", cipher, s);
      return null;
    }
    PKIHeaderData headerData = new PKIHeaderData();
    try {
      headerData.timestamp = Long.parseLong(s.substring(splitPoint + 1));
      headerData.userName = s.substring(0, splitPoint);
      log.debug("Successfully decrypted header {} {}", headerData.userName, headerData.timestamp);
      return headerData;
    } catch (NumberFormatException e) {
      log.warn("Invalid cipher {}", cipher);
      return null;
    }
  }

  PublicKey getRemotePublicKey(String nodename) {
    if (!cores.getZkController().getZkStateReader().getClusterState().getLiveNodes().contains(nodename)) return null;
    String url = cores.getZkController().getZkStateReader().getBaseUrlForNodeName(nodename);
    HttpEntity entity = null;
    try {
      String uri = url + PublicKeyHandler.PATH + "?wt=json&omitHeader=true";
      log.debug("Fetching fresh public key from : {}",uri);
      HttpResponse rsp = cores.getUpdateShardHandler().getDefaultHttpClient()
          .execute(new HttpGet(uri), HttpClientUtil.createNewHttpClientRequestContext());
      entity  = rsp.getEntity();
      byte[] bytes = EntityUtils.toByteArray(entity);
      @SuppressWarnings({"rawtypes"})
      Map m = (Map) Utils.fromJSON(bytes);
      String key = (String) m.get("key");
      if (key == null) {
        log.error("No key available from {} {}", url, PublicKeyHandler.PATH);
        return null;
      } else {
        log.info("New Key obtained from  node: {} / {}", nodename, key);
      }
      PublicKey pubKey = CryptoKeys.deserializeX509PublicKey(key);
      keyCache.put(nodename, pubKey);
      return pubKey;
    } catch (Exception e) {
      log.error("Exception trying to get public key from : {}", url, e);
      return null;
    } finally {
      Utils.consumeFully(entity);
    }

  }

  @Override
  public void setup(Http2SolrClient client) {
    final HttpListenerFactory.RequestResponseListener listener = new HttpListenerFactory.RequestResponseListener() {
      @Override
      public void onQueued(Request request) {
        log.trace("onQueued: {}", request);
        if (cores.getAuthenticationPlugin() == null) {
          log.trace("no authentication plugin, skipping");
          return;
        }
        if (!cores.getAuthenticationPlugin().interceptInternodeRequest(request)) {
          if (log.isDebugEnabled()) {
            log.debug("{} secures this internode request", this.getClass().getSimpleName());
          }
          generateToken().ifPresent(s -> request.header(HEADER, myNodeName + " " + s));
        } else {
          if (log.isDebugEnabled()) {
            log.debug("{} secures this internode request", cores.getAuthenticationPlugin().getClass().getSimpleName());
          }
        }
      }
    };
    client.addListenerFactory(() -> listener);
  }

  @Override
  public SolrHttpClientBuilder getHttpClientBuilder(SolrHttpClientBuilder builder) {
    HttpClientUtil.addRequestInterceptor(interceptor);
    interceptorRegistered = true;
    return builder;
  }

  public boolean needsAuthorization(HttpServletRequest req) {
    return req.getUserPrincipal() != SU;
  }

  private class HttpHeaderClientInterceptor implements HttpRequestInterceptor {

    public HttpHeaderClientInterceptor() {
    }

    @Override
    public void process(HttpRequest httpRequest, HttpContext httpContext) throws HttpException, IOException {
      if (cores.getAuthenticationPlugin() == null) {
        return;
      }
      if (!cores.getAuthenticationPlugin().interceptInternodeRequest(httpRequest, httpContext)) {
        if (log.isDebugEnabled()) {
          log.debug("{} secures this internode request", this.getClass().getSimpleName());
        }
        setHeader(httpRequest);
      } else {
        if (log.isDebugEnabled()) {
          log.debug("{} secures this internode request", cores.getAuthenticationPlugin().getClass().getSimpleName());
        }
      }
    }
  }

  @SuppressForbidden(reason = "Needs currentTimeMillis to set current time in header")
  private Optional<String> generateToken() {
    SolrRequestInfo reqInfo = getRequestInfo();
    String usr;
    if (reqInfo != null) {
      Principal principal = reqInfo.getUserPrincipal();
      if (principal == null) {
        log.debug("generateToken: principal is null");
        //this had a request but not authenticated
        //so we don't not need to set a principal
        return Optional.empty();
      } else {
        usr = principal.getName();
      }
    } else {
      if (!isSolrThread()) {
        //if this is not running inside a Solr threadpool (as in testcases)
        // then no need to add any header
        log.debug("generateToken: not a solr (server) thread");
        return Optional.empty();
      }
      //this request seems to be originated from Solr itself
      usr = "$"; //special name to denote the user is the node itself
    }

    String s = usr + " " + System.currentTimeMillis();

    byte[] payload = s.getBytes(UTF_8);
    byte[] payloadCipher = publicKeyHandler.keyPair.encrypt(ByteBuffer.wrap(payload));
    String base64Cipher = Base64.byteArrayToBase64(payloadCipher);
    log.trace("generateToken: usr={} token={}", usr, base64Cipher);
    return Optional.of(base64Cipher);
  }

  void setHeader(HttpRequest httpRequest) {
    generateToken().ifPresent(s -> httpRequest.setHeader(HEADER, myNodeName + " " + s));
  }

  boolean isSolrThread() {
    return ExecutorUtil.isSolrServerThread();
  }

  SolrRequestInfo getRequestInfo() {
    return SolrRequestInfo.getRequestInfo();
  }

  @Override
  public void close() throws IOException {
    HttpClientUtil.removeRequestInterceptor(interceptor);
    interceptorRegistered = false;
  }

  public String getPublicKey() {
    return publicKeyHandler.getPublicKey();
  }

  public static final String HEADER = "SolrAuth";
  public static final String NODE_IS_USER = "$";
  // special principal to denote the cluster member
  private static final Principal SU = new BasicUserPrincipal("$");
}
