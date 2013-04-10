/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.gateway.provider.federation.jwt.filter;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.AccessController;
import java.util.HashMap;

import javax.security.auth.Subject;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.hadoop.gateway.filter.security.AbstractIdentityAssertionFilter;
import org.apache.hadoop.gateway.provider.federation.jwt.AccessToken;
import org.apache.hadoop.gateway.provider.federation.jwt.JWTToken;
import org.apache.hadoop.gateway.services.GatewayServices;
import org.apache.hadoop.gateway.services.security.CryptoService;
import org.apache.hadoop.gateway.util.JsonUtils;

public class JWTAccessTokenAssertionFilter extends AbstractIdentityAssertionFilter {
  private static final String EXPIRES_IN = "expires_in";
  private static final String TOKEN_TYPE = "token_type";
  private static final String ACCESS_TOKEN = "access_token";
  private static final String BEARER = "Bearer ";
  private long validity;
  private CryptoService crypto = null;

  @Override
  public void init( FilterConfig filterConfig ) throws ServletException {
    super.init(filterConfig);
    String validityStr = filterConfig.getInitParameter("validity");
    if (validityStr == null) {
      validityStr = "3600"; // 1 hr. in secs
    }
    validity = Long.parseLong(validityStr);

    GatewayServices services = (GatewayServices) filterConfig.getServletContext().getAttribute(GatewayServices.GATEWAY_SERVICES_ATTRIBUTE);
    crypto = (CryptoService) services.getService(GatewayServices.CRYPTO_SERVICE);
  }
  
  @Override
  public void doFilter(ServletRequest request, ServletResponse response,
      FilterChain chain) throws IOException, ServletException {
    String jsonResponse = null;
    
    String header = ((HttpServletRequest) request).getHeader("Authorization");
    if (header != null && header.startsWith(BEARER)) {
      // what follows the bearer designator should be the JWT token being used to request or as an access token
      String wireToken = header.substring(BEARER.length());
      JWTToken token = JWTToken.parseToken(wireToken);
      
      // authorization of the user for the requested service (and resource?) should have been done by
      // the JWTFederationFilter - once we get here we can assume that it is authorized and we just need
      // to assert the identity via an access token

      Subject subject = Subject.getSubject(AccessController.getContext());
      String principalName = getPrincipalName(subject);
      principalName = mapper.mapPrincipal(principalName);
      
      // calculate expiration timestamp: validity * 1000 + currentTimeInMillis
      long expires = System.currentTimeMillis() + validity * 1000;
      
      String accessToken = getAccessToken(principalName, expires);
      
      HashMap<String, Object> map = new HashMap<String, Object>();
      // TODO: populate map from JWT authorization code
      map.put(ACCESS_TOKEN, accessToken);
      map.put(TOKEN_TYPE, BEARER);
      map.put(EXPIRES_IN, expires);
      
      jsonResponse = JsonUtils.renderAsJsonString(map);
      
      response.getWriter().write(jsonResponse);
      response.getWriter().flush();
      return; // break filter chain
    }
    else {
      // no token provided in header
      // something is really wrong since the JWTFederationFilter should have verified its existence already
      // TODO: may have to check cookie and url as well before sending error
      ((HttpServletResponse) response).sendError(HttpServletResponse.SC_UNAUTHORIZED);
      return; //break filter chain
    }
  }

  private String getAccessToken(String principalName, long expires) {
    String accessToken = null;

    AccessToken token = new AccessToken(crypto, principalName, expires);
    accessToken = token.toString();
    
    return accessToken;
  }

}
