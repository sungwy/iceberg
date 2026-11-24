/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableScan;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.SessionCatalog.SessionContext;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.inmemory.InMemoryCatalog;
import org.apache.iceberg.inmemory.InMemoryFileIO;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableList;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.rest.HTTPHeaders.HTTPHeader;
import org.apache.iceberg.rest.auth.AuthManager;
import org.apache.iceberg.rest.auth.AuthManagers;
import org.apache.iceberg.rest.auth.AuthProperties;
import org.apache.iceberg.rest.auth.AuthSession;
import org.apache.iceberg.rest.auth.BearerAuthManager;
import org.apache.iceberg.rest.auth.BearerTokenSource;
import org.apache.iceberg.rest.auth.DefaultAuthSession;
import org.apache.iceberg.rest.auth.FileIOAuthBinder;
import org.apache.iceberg.rest.auth.FileIOSession;
import org.apache.iceberg.rest.auth.OAuth2Properties;
import org.apache.iceberg.rest.auth.OAuth2Util;
import org.apache.iceberg.rest.credentials.ImmutableCredential;
import org.apache.iceberg.rest.responses.ConfigResponse;
import org.apache.iceberg.rest.responses.LoadTableResponse;
import org.apache.iceberg.rest.responses.OAuthTokenResponse;
import org.apache.iceberg.rest.responses.PlanTableScanResponse;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestRESTSessionCatalogFileIOSession {

  private static final Namespace NAMESPACE = Namespace.of("ns");
  private static final TableIdentifier TABLE = TableIdentifier.of(NAMESPACE, "t");
  private static final TableIdentifier OTHER_TABLE = TableIdentifier.of(NAMESPACE, "other");
  private static final TableIdentifier TOKEN_TABLE = TableIdentifier.of(NAMESPACE, "token_table");
  private static final String TABLE_TOKEN = "server-table-token";
  private static final Schema SCHEMA =
      new Schema(Types.NestedField.required(1, "id", Types.LongType.get()));
  private static final HTTPRequest REQUEST =
      ImmutableHTTPRequest.builder()
          .baseUri(URI.create("http://localhost:8181/catalog"))
          .method(HTTPRequest.HTTPMethod.GET)
          .path("v1/config")
          .build();

  @TempDir private Path temp;

  private InMemoryCatalog backendCatalog;
  private SessionAdapter adapter;
  private RESTSessionCatalog catalog;

  private static class CustomSession implements AuthSession, BearerTokenSource {
    private final String token;

    private CustomSession(String token) {
      this.token = token;
    }

    @Override
    public String token() {
      return token;
    }

    @Override
    public HTTPRequest authenticate(HTTPRequest request) {
      return DefaultAuthSession.of(HTTPHeaders.of(OAuth2Util.authHeaders(token)))
          .authenticate(request);
    }

    @Override
    public void close() {}
  }

  public static class CustomAuthManager implements AuthManager {
    private static final AtomicReference<AuthSession> SESSION = new AtomicReference<>();

    @SuppressWarnings("checkstyle:RedundantModifier")
    public CustomAuthManager(String name) {}

    @Override
    public AuthSession catalogSession(RESTClient sharedClient, Map<String, String> properties) {
      return AuthSession.EMPTY;
    }

    @Override
    public AuthSession contextualSession(SessionContext context, AuthSession parent) {
      AuthSession session = SESSION.get();
      return session != null ? session : new CustomSession("custom-" + context.sessionId());
    }

    @Override
    public void close() {}
  }

  private static class SessionAdapter extends RESTCatalogAdapter {
    private final AtomicInteger issuedTokens = new AtomicInteger();
    private final Map<String, String> serverConfig;
    private final Map<String, String> tableConfig;
    private volatile String loadTableAuthorization = null;

    SessionAdapter(
        Catalog catalog, Map<String, String> serverConfig, Map<String, String> tableConfig) {
      super(catalog);
      this.serverConfig = serverConfig;
      this.tableConfig = tableConfig;
    }

    @Override
    public <T extends RESTResponse> T handleRequest(
        Route route,
        Map<String, String> vars,
        HTTPRequest httpRequest,
        Class<T> responseType,
        Consumer<Map<String, String>> responseHeaders) {
      T response = super.handleRequest(route, vars, httpRequest, responseType, responseHeaders);
      if (response instanceof OAuthTokenResponse token) {
        return castResponse(
            responseType,
            OAuthTokenResponse.builder()
                .withToken(encode(token.token() + "#" + issuedTokens.incrementAndGet()))
                .withTokenType(token.tokenType())
                .withIssuedTokenType(token.issuedTokenType())
                .build());
      } else if (response instanceof ConfigResponse config) {
        return castResponse(
            responseType,
            ConfigResponse.builder()
                .withDefaults(config.defaults())
                .withOverrides(config.overrides())
                .withOverrides(serverConfig)
                .withEndpoints(config.endpoints())
                .build());
      } else if (route == Route.LOAD_TABLE && response instanceof LoadTableResponse table) {
        this.loadTableAuthorization = authorization(httpRequest);
        LoadTableResponse.Builder builder =
            LoadTableResponse.builder()
                .withTableMetadata(table.tableMetadata())
                .addAllConfig(table.config())
                .addAllConfig(tableConfig)
                .addAllCredentials(table.credentials());
        if (TOKEN_TABLE.name().equals(vars.get("table"))) {
          builder.addConfig(OAuth2Properties.TOKEN, TABLE_TOKEN);
        }

        return castResponse(responseType, builder.build());
      } else if (response instanceof PlanTableScanResponse plan) {
        return castResponse(
            responseType,
            plan.toBuilder()
                .withCredentials(
                    ImmutableList.of(
                        ImmutableCredential.builder()
                            .prefix("memory")
                            .putConfig("key", "value")
                            .build()))
                .build());
      }

      return response;
    }
  }

  @BeforeEach
  void before() {
    this.backendCatalog = new InMemoryCatalog();
    backendCatalog.initialize(
        "backend",
        ImmutableMap.of(CatalogProperties.WAREHOUSE_LOCATION, temp.toFile().getAbsolutePath()));
    backendCatalog.createNamespace(NAMESPACE);
    backendCatalog.createTable(TABLE, SCHEMA);
    backendCatalog.createTable(OTHER_TABLE, SCHEMA);
    backendCatalog.createTable(TOKEN_TABLE, SCHEMA);
  }

  @AfterEach
  void after() throws IOException {
    CustomAuthManager.SESSION.set(null);
    if (catalog != null) {
      catalog.close();
    }

    backendCatalog.close();
  }

  private RESTSessionCatalog catalog(Map<String, String> properties) {
    return catalog(properties, ImmutableMap.of(), ImmutableMap.of(), null);
  }

  private RESTSessionCatalog catalog(
      Map<String, String> properties,
      Map<String, String> serverConfig,
      Map<String, String> tableConfig,
      BiFunction<SessionContext, Map<String, String>, FileIO> ioBuilder) {
    return catalog(
        properties, serverConfig, tableConfig, ioBuilder, FileIOAuthBinder.staticBearerToken());
  }

  private RESTSessionCatalog catalog(Map<String, String> properties, FileIOAuthBinder binder) {
    return catalog(properties, ImmutableMap.of(), ImmutableMap.of(), null, binder);
  }

  private RESTSessionCatalog catalog(
      Map<String, String> properties,
      Map<String, String> serverConfig,
      Map<String, String> tableConfig,
      BiFunction<SessionContext, Map<String, String>, FileIO> ioBuilder,
      FileIOAuthBinder binder) {
    this.adapter = new SessionAdapter(backendCatalog, serverConfig, tableConfig);
    this.catalog = newCatalog(adapter, properties, ioBuilder, binder);
    return catalog;
  }

  private static RESTSessionCatalog newCatalog(
      RESTCatalogAdapter catalogAdapter,
      Map<String, String> properties,
      BiFunction<SessionContext, Map<String, String>, FileIO> ioBuilder,
      FileIOAuthBinder binder) {
    RESTSessionCatalog sessionCatalog =
        new RESTSessionCatalog(config -> catalogAdapter, ioBuilder, binder);
    sessionCatalog.initialize(
        "prod",
        ImmutableMap.<String, String>builder()
            .put(CatalogProperties.URI, "http://localhost:8181/catalog")
            .put(OAuth2Properties.TOKEN, "catalog-token")
            .put(OAuth2Properties.OAUTH2_SERVER_URI, "v1/oauth/tokens")
            .put(CatalogProperties.FILE_IO_IMPL, InMemoryFileIO.class.getName())
            .putAll(properties)
            .buildKeepingLast());
    return sessionCatalog;
  }

  private RESTSessionCatalog enabledCatalog(Map<String, String> properties) {
    return catalog(enabled(properties));
  }

  private static Map<String, String> enabled(Map<String, String> properties) {
    return ImmutableMap.<String, String>builder()
        .put(AuthProperties.FILE_IO_SESSION_ENABLED, "true")
        .putAll(properties)
        .buildKeepingLast();
  }

  private static SessionContext userContext() {
    return userContext("user");
  }

  private static SessionContext userContext(String user) {
    return new SessionContext(
        UUID.randomUUID().toString(),
        user,
        ImmutableMap.of(OAuth2Properties.CREDENTIAL, user + ":secret"),
        ImmutableMap.of());
  }

  private static String encode(String token) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(token.getBytes(StandardCharsets.UTF_8));
  }

  private static String decodedBearerToken(String authorization) {
    return new String(
        Base64.getUrlDecoder().decode(authorization.substring("Bearer ".length())),
        StandardCharsets.UTF_8);
  }

  private static String authorization(HTTPRequest request) {
    return request.headers().firstEntry("Authorization").map(HTTPHeader::value).orElse(null);
  }

  private String fileIOAuthorization(FileIO fileIO) {
    try (AuthManager fileIOAuthManager =
        AuthManagers.loadAuthManager("file-io", fileIO.properties())) {
      assertThat(fileIOAuthManager).isInstanceOf(BearerAuthManager.class);
      AuthSession session = fileIOAuthManager.catalogSession(adapter, fileIO.properties());
      return authorization(session.authenticate(REQUEST));
    }
  }

  @Test
  void tableFileIOPresentsSessionToken() {
    Table table = enabledCatalog(ImmutableMap.of()).loadTable(userContext(), TABLE);

    assertThat(table.io().properties())
        .containsEntry(AuthProperties.AUTH_TYPE, AuthProperties.AUTH_TYPE_BEARER);
    assertThat(fileIOAuthorization(table.io())).isEqualTo(adapter.loadTableAuthorization);
  }

  @Test
  void tokenExchangeSessionPresentsExchangedToken() {
    SessionContext context =
        new SessionContext(
            UUID.randomUUID().toString(),
            "user",
            ImmutableMap.of(OAuth2Properties.ACCESS_TOKEN_TYPE, "subject-token"),
            ImmutableMap.of());
    Table table = enabledCatalog(ImmutableMap.of()).loadTable(context, TABLE);

    String authorization = fileIOAuthorization(table.io());
    assertThat(authorization).isEqualTo(adapter.loadTableAuthorization);
    assertThat(decodedBearerToken(authorization))
        .startsWith("token-exchange-token:sub=subject-token");
  }

  @Test
  void sessionsPresentTheirOwnTokens() {
    RESTSessionCatalog sessionCatalog = enabledCatalog(ImmutableMap.of());

    Table aliceTable = sessionCatalog.loadTable(userContext("alice"), TABLE);
    Table bobTable = sessionCatalog.loadTable(userContext("bob"), TABLE);

    assertThat(decodedBearerToken(fileIOAuthorization(aliceTable.io()))).contains("sub=alice");
    assertThat(decodedBearerToken(fileIOAuthorization(bobTable.io()))).contains("sub=bob");
  }

  @Test
  void tableTokenSessionPresentsTableToken() {
    Table table = enabledCatalog(ImmutableMap.of()).loadTable(userContext(), TOKEN_TABLE);

    assertThat(table.io().properties()).doesNotContainKey(OAuth2Properties.TOKEN);
    assertThat(fileIOAuthorization(table.io())).isEqualTo("Bearer " + TABLE_TOKEN);
  }

  @Test
  void customAuthManagerSessionIsPresented() {
    SessionContext context = userContext();
    Table table =
        enabledCatalog(ImmutableMap.of(AuthProperties.AUTH_TYPE, CustomAuthManager.class.getName()))
            .loadTable(context, TABLE);

    assertThat(fileIOAuthorization(table.io())).isEqualTo("Bearer custom-" + context.sessionId());
  }

  @Test
  void clientAuthPropertiesAreRemoved() {
    Map<String, String> serverTableConfig =
        ImmutableMap.<String, String>builder()
            .put(AuthProperties.AUTH_TYPE, AuthProperties.AUTH_TYPE_BASIC)
            .put(AuthProperties.BEARER_TOKEN, "server-token")
            .put("rest.auth.bearer.token-file", "/tmp/server-token")
            .put("rest.sigv4-enabled", "true")
            .put("header.Authorization", "Bearer server-header")
            .put("header.X-Custom", "kept")
            .buildOrThrow();
    Table table =
        catalog(
                enabled(ImmutableMap.of(OAuth2Properties.CREDENTIAL, "client:secret")),
                ImmutableMap.of(),
                serverTableConfig,
                null)
            .loadTable(userContext(), TABLE);

    assertThat(table.io().properties())
        .doesNotContainKeys(
            OAuth2Properties.CREDENTIAL,
            OAuth2Properties.TOKEN,
            "rest.auth.bearer.token-file",
            "rest.sigv4-enabled",
            "header.Authorization")
        .containsEntry(AuthProperties.AUTH_TYPE, AuthProperties.AUTH_TYPE_BEARER)
        .containsEntry("header.X-Custom", "kept");
    assertThat(fileIOAuthorization(table.io())).isEqualTo(adapter.loadTableAuthorization);
  }

  @Test
  void tableSubjectTokenIsNotPassedToFileIO() {
    Table table =
        catalog(
                enabled(ImmutableMap.of()),
                ImmutableMap.of(),
                ImmutableMap.of(OAuth2Properties.ACCESS_TOKEN_TYPE, "table-subject-token"),
                null)
            .loadTable(userContext(), TABLE);

    assertThat(table.io().properties()).doesNotContainKey(OAuth2Properties.ACCESS_TOKEN_TYPE);
    assertThat(decodedBearerToken(fileIOAuthorization(table.io())))
        .startsWith("token-exchange-token:sub=table-subject-token");
  }

  @Test
  void ioBuilderReceivesSessionProperties() {
    AtomicReference<Map<String, String>> ioProperties = new AtomicReference<>();
    catalog(
            enabled(ImmutableMap.of()),
            ImmutableMap.of(),
            ImmutableMap.of(),
            (context, properties) -> {
              ioProperties.set(properties);
              return CatalogUtil.loadFileIO(InMemoryFileIO.class.getName(), properties, null);
            })
        .loadTable(userContext(), TABLE);

    assertThat(ioProperties.get())
        .containsEntry(AuthProperties.AUTH_TYPE, AuthProperties.AUTH_TYPE_BEARER)
        .containsEntry(
            AuthProperties.BEARER_TOKEN,
            adapter.loadTableAuthorization.substring("Bearer ".length()));
  }

  @Test
  void sessionWithoutTokenSourceFails() {
    AuthSession basicSession =
        DefaultAuthSession.of(HTTPHeaders.of(HTTPHeader.of("Authorization", "Basic dXNlcg==")));
    CustomAuthManager.SESSION.set(basicSession);
    RESTSessionCatalog sessionCatalog =
        enabledCatalog(
            ImmutableMap.of(AuthProperties.AUTH_TYPE, CustomAuthManager.class.getName()));

    assertThatThrownBy(() -> sessionCatalog.loadTable(userContext(), TABLE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(
            "Cannot bind FileIO for table %s to its session: %s is not a bearer token source",
            TABLE, basicSession.getClass().getName());
  }

  @Test
  void sessionWithoutTokenFails() {
    CustomAuthManager.SESSION.set(new CustomSession(null));
    RESTSessionCatalog sessionCatalog =
        enabledCatalog(
            ImmutableMap.of(AuthProperties.AUTH_TYPE, CustomAuthManager.class.getName()));

    assertThatThrownBy(() -> sessionCatalog.loadTable(userContext(), TABLE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Invalid bearer token: null");
  }

  @Test
  void sessionWithInvalidBearerTokenFails() {
    CustomAuthManager.SESSION.set(new CustomSession("subject:token"));
    RESTSessionCatalog sessionCatalog =
        enabledCatalog(
            ImmutableMap.of(AuthProperties.AUTH_TYPE, CustomAuthManager.class.getName()));

    assertThatThrownBy(() -> sessionCatalog.loadTable(userContext(), TABLE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Invalid bearer token: contains characters not allowed by RFC 6750");
  }

  @Test
  void catalogSessionKeepsCatalogProperties() {
    Table table = enabledCatalog(ImmutableMap.of()).loadTable(SessionContext.createEmpty(), TABLE);

    assertThat(table.io().properties())
        .doesNotContainKey(AuthProperties.AUTH_TYPE)
        .containsEntry(OAuth2Properties.TOKEN, "catalog-token");
  }

  @Test
  void disabledByDefault() {
    Table table = catalog(ImmutableMap.of()).loadTable(userContext(), TABLE);

    assertThat(table.io().properties()).doesNotContainKey(AuthProperties.AUTH_TYPE);
  }

  @Test
  void serverConfigCannotEnableFileIOSessions() {
    Table table =
        catalog(
                ImmutableMap.of(),
                ImmutableMap.of(AuthProperties.FILE_IO_SESSION_ENABLED, "true"),
                ImmutableMap.of(),
                null)
            .loadTable(userContext(), TABLE);

    assertThat(table.io().properties()).doesNotContainKey(AuthProperties.AUTH_TYPE);
  }

  @Test
  void scanPlanningFileIOPresentsSessionToken() throws IOException {
    Table table =
        enabledCatalog(
                ImmutableMap.of(
                    RESTCatalogProperties.SCAN_PLANNING_MODE,
                    RESTCatalogProperties.ScanPlanningMode.SERVER.modeName()))
            .loadTable(userContext(), TABLE);

    TableScan scan = table.newScan();
    try (CloseableIterable<?> tasks = scan.planFiles()) {
      assertThat(tasks).isEmpty();
    }

    FileIO scanIO = ((RESTTableScan) scan).fileIO().get();
    assertThat(scanIO).isNotSameAs(table.io());
    assertThat(scanIO.properties()).doesNotContainKey(OAuth2Properties.TOKEN);
    assertThat(fileIOAuthorization(scanIO)).isEqualTo(fileIOAuthorization(table.io()));
  }

  private static class RecordingBinder implements FileIOAuthBinder {
    private final List<FileIOSession> fileIOSessions = new CopyOnWriteArrayList<>();

    @Override
    public Map<String, String> fileIOAuthProperties(FileIOSession fileIOSession) {
      fileIOSessions.add(fileIOSession);
      return ImmutableMap.of(
          AuthProperties.AUTH_TYPE,
          AuthProperties.AUTH_TYPE_BEARER,
          "rest.auth.bearer.token-file",
          "/tokens/" + encode(fileIOSession.sessionKey()));
    }

    private FileIOSession last() {
      return fileIOSessions.get(fileIOSessions.size() - 1);
    }
  }

  @Test
  void binderChoosesFileIOAuthProperties() {
    RecordingBinder binder = new RecordingBinder();
    Table table =
        catalog(enabled(ImmutableMap.of(OAuth2Properties.CREDENTIAL, "client:secret")), binder)
            .loadTable(userContext(), TABLE);

    FileIOSession fileIOSession = binder.last();
    assertThat(fileIOSession.tableIdentifier()).isEqualTo(TABLE);
    assertThat("Bearer " + fileIOSession.bearerTokenSource().token())
        .isEqualTo(adapter.loadTableAuthorization);
    assertThat(table.io().properties())
        .containsEntry(AuthProperties.AUTH_TYPE, AuthProperties.AUTH_TYPE_BEARER)
        .containsEntry(
            "rest.auth.bearer.token-file", "/tokens/" + encode(fileIOSession.sessionKey()))
        .doesNotContainKeys(
            OAuth2Properties.CREDENTIAL, OAuth2Properties.TOKEN, AuthProperties.BEARER_TOKEN);
  }

  @Test
  void tablesOfOneSessionShareSessionKey() {
    RecordingBinder binder = new RecordingBinder();
    RESTSessionCatalog sessionCatalog = catalog(enabled(ImmutableMap.of()), binder);
    SessionContext context = userContext();

    sessionCatalog.loadTable(context, TABLE);
    String sessionKey = binder.last().sessionKey();
    sessionCatalog.loadTable(context, OTHER_TABLE);

    assertThat(binder.last().sessionKey()).isEqualTo(sessionKey);
  }

  @Test
  void sessionsHaveDistinctSessionKeys() {
    RecordingBinder binder = new RecordingBinder();
    RESTSessionCatalog sessionCatalog = catalog(enabled(ImmutableMap.of()), binder);

    sessionCatalog.loadTable(userContext("alice"), TABLE);
    String aliceSessionKey = binder.last().sessionKey();
    sessionCatalog.loadTable(userContext("bob"), TABLE);

    assertThat(binder.last().sessionKey()).isNotEqualTo(aliceSessionKey);
  }

  @Test
  void catalogsHaveDistinctSessionKeys() throws IOException {
    RecordingBinder binder = new RecordingBinder();
    SessionContext context = userContext();
    catalog(enabled(ImmutableMap.of()), binder).loadTable(context, TABLE);
    String sessionKey = binder.last().sessionKey();

    try (RESTSessionCatalog sameName =
        newCatalog(
            new SessionAdapter(backendCatalog, ImmutableMap.of(), ImmutableMap.of()),
            enabled(ImmutableMap.of()),
            null,
            binder)) {
      sameName.loadTable(context, TABLE);

      assertThat(binder.last().sessionKey()).isNotEqualTo(sessionKey);
    }
  }

  @Test
  void tableTokenSessionHasTableSessionKey() {
    RecordingBinder binder = new RecordingBinder();
    RESTSessionCatalog sessionCatalog = catalog(enabled(ImmutableMap.of()), binder);
    SessionContext context = userContext();

    sessionCatalog.loadTable(context, TABLE);
    String sessionKey = binder.last().sessionKey();
    sessionCatalog.loadTable(context, TOKEN_TABLE);

    assertThat(binder.last().sessionKey()).startsWith(sessionKey + "/").isNotEqualTo(sessionKey);
    assertThat(binder.last().bearerTokenSource().token()).isEqualTo(TABLE_TOKEN);
  }

  @Test
  void bearerTokenSourceDerivesTheSessionAgain() {
    RecordingBinder binder = new RecordingBinder();
    catalog(enabled(ImmutableMap.of(CatalogProperties.AUTH_SESSION_TIMEOUT_MS, "0")), binder)
        .loadTable(userContext(), TABLE);
    BearerTokenSource bearerTokenSource = binder.last().bearerTokenSource();

    String firstToken = bearerTokenSource.token();

    assertThat(bearerTokenSource.token()).isNotNull().isNotEqualTo(firstToken);
  }

  @Test
  void closedCatalogProvidesNoToken() throws IOException {
    RecordingBinder binder = new RecordingBinder();
    catalog(enabled(ImmutableMap.of()), binder).loadTable(userContext(), TABLE);
    BearerTokenSource bearerTokenSource = binder.last().bearerTokenSource();

    catalog.close();
    this.catalog = null;

    assertThat(bearerTokenSource.token()).isNull();
  }

  @Test
  void binderIsNotCalledForCatalogSession() {
    RecordingBinder binder = new RecordingBinder();
    catalog(enabled(ImmutableMap.of()), binder).loadTable(SessionContext.createEmpty(), TABLE);

    assertThat(binder.fileIOSessions).isEmpty();
  }

  @Test
  void binderWithoutPropertiesFails() {
    RESTSessionCatalog sessionCatalog =
        catalog(enabled(ImmutableMap.of()), fileIOSession -> ImmutableMap.of());

    assertThatThrownBy(() -> sessionCatalog.loadTable(userContext(), TABLE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Cannot bind FileIO for table %s to its session: no auth properties", TABLE);
  }

  @Test
  void binderOutsideAuthPropertiesFails() {
    RESTSessionCatalog sessionCatalog =
        catalog(
            enabled(ImmutableMap.of()),
            fileIOSession -> ImmutableMap.of(CatalogProperties.FILE_IO_IMPL, "other"));

    assertThatThrownBy(() -> sessionCatalog.loadTable(userContext(), TABLE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(
            "Cannot bind FileIO for table %s to its session: invalid auth property %s",
            TABLE, CatalogProperties.FILE_IO_IMPL);
  }
}
