/*
 * Zed Attack Proxy (ZAP) and its related class files.
 *
 * ZAP is an HTTP/HTTPS proxy for assessing web application security.
 *
 * Copyright 2023 The ZAP Development Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.zaproxy.addon.client;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import net.sf.json.JSONObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.parosproxy.paros.control.Control;
import org.parosproxy.paros.network.HttpMessage;
import org.parosproxy.paros.network.HttpRequestHeader;
import org.zaproxy.addon.network.ExtensionNetwork;
import org.zaproxy.addon.network.server.ServerInfo;
import org.zaproxy.zap.extension.api.API;
import org.zaproxy.zap.extension.api.ApiAction;
import org.zaproxy.zap.extension.api.ApiException;
import org.zaproxy.zap.extension.api.ApiImplementor;
import org.zaproxy.zap.extension.api.ApiResponse;
import org.zaproxy.zap.extension.api.ApiResponseElement;
import org.zaproxy.zap.utils.ThreadUtils;

public class ClientIntegrationAPI extends ApiImplementor {
    private static final String PREFIX = "client";

    private static final String ACTION_REPORT_OBJECT = "reportObject";
    private static final String ACTION_REPORT_EVENT = "reportEvent";
    private static final String ACTION_REPORT_ZEST_SCRIPT = "reportZestScript";

    private static final String PARAM_OBJECT_JSON = "objectJson";
    private static final String PARAM_EVENT_JSON = "eventJson";
    private static final String PARAM_SCRIPT_JSON = "scriptJson";

    private static final Logger LOGGER = LogManager.getLogger(ClientIntegrationAPI.class);

    private ExtensionClientIntegration extension;

    private String callbackUrl;

    public ClientIntegrationAPI(ExtensionClientIntegration extension) {
        this.extension = extension;
        this.addApiAction(new ApiAction(ACTION_REPORT_OBJECT, new String[] {PARAM_OBJECT_JSON}));
        this.addApiAction(new ApiAction(ACTION_REPORT_EVENT, new String[] {PARAM_EVENT_JSON}));
        this.addApiAction(
                new ApiAction(ACTION_REPORT_ZEST_SCRIPT, new String[] {PARAM_SCRIPT_JSON}));

        ServerInfo serverInfo =
                Control.getSingleton()
                        .getExtensionLoader()
                        .getExtension(ExtensionNetwork.class)
                        .getMainProxyServerInfo();

        callbackUrl =
                API.getInstance()
                        .getCallBackUrl(
                                this,
                                "http://" + serverInfo.getAddress() + ":" + serverInfo.getPort());
        LOGGER.debug("Client API callback URL: {}", callbackUrl);
    }

    @Override
    public String getPrefix() {
        return PREFIX;
    }

    protected String getCallbackUrl() {
        return callbackUrl;
    }

    private void handleReportObject(JSONObject json) {
        ReportedNode rnode = new ReportedNode(json);
        if (!"A".equals(rnode.getNodeName())) {
            // Dont add links - they flood the table
            this.extension.addReportedObject(rnode);
        }
        Object url = json.get("url");
        if (url instanceof String) {
            ThreadUtils.invokeAndWaitHandled(
                    () -> {
                        ClientNode node = this.extension.getOrAddClientNode((String) url, false);
                        ClientSideDetails details = node.getUserObject();
                        boolean wasVisited = details.isVisited();
                        ClientSideComponent component = new ClientSideComponent(json);
                        boolean componentAdded = details.addComponent(component);
                        if (!wasVisited || componentAdded) {
                            details.setVisited(true);
                            this.extension.clientNodeChanged(node);
                        }
                        if (component.isStorageEvent()) {
                            String storageUrl = node.getSite() + component.getTypeForDisplay();
                            ClientNode storageNode =
                                    this.extension.getOrAddClientNode(storageUrl, true);
                            ClientSideDetails storageDetails = storageNode.getUserObject();
                            storageDetails.setStorage(true);
                            storageDetails.addComponent(component);
                            this.extension.clientNodeChanged(storageNode);
                        }
                    });
        } else {
            LOGGER.debug("Not got url:(: {}", url);
        }
        Object href = json.get("href");
        if (href instanceof String && ((String) href).toLowerCase(Locale.ROOT).startsWith("http")) {
            ThreadUtils.invokeAndWaitHandled(
                    () -> this.extension.getOrAddClientNode((String) href, false));
        }
    }

    @Override
    public ApiResponse handleApiAction(String name, JSONObject params) throws ApiException {
        JSONObject json;
        switch (name) {
            case ACTION_REPORT_OBJECT:
                String objJson = this.getParam(params, PARAM_OBJECT_JSON, "");
                LOGGER.debug("Got object: {}", objJson);
                json = JSONObject.fromObject(objJson);
                handleReportObject(json);
                break;

            case ACTION_REPORT_EVENT:
                String eventJson = this.getParam(params, PARAM_EVENT_JSON, "");
                LOGGER.debug("Got event: {}", eventJson);
                json = JSONObject.fromObject(eventJson);
                this.extension.addReportedObject(new ReportedEvent(json));
                break;

            case ACTION_REPORT_ZEST_SCRIPT:
                String scriptJson = this.getParam(params, PARAM_SCRIPT_JSON, "");
                LOGGER.debug("Got script: {}", scriptJson);
                try {
                    this.extension.addZestStatement(scriptJson);
                } catch (Exception e) {
                    LOGGER.debug(e);
                }
                break;

            default:
                throw new ApiException(ApiException.Type.BAD_ACTION);
        }

        return ApiResponseElement.OK;
    }

    static String decodeParamString(String body, String param) {
        // Should always start with 'param'=
        String str = body.substring(param.length() + 1);
        int apikeyIndex = str.indexOf("&apikey=");
        if (apikeyIndex > 0) {
            str = str.substring(0, apikeyIndex);
        }
        str = URLDecoder.decode(str, StandardCharsets.UTF_8);
        return str;
    }

    static JSONObject decodeParam(String body, String param) {
        String str = decodeParamString(body, param);
        return JSONObject.fromObject(str);
    }

    @Override
    public String handleCallBack(HttpMessage msg) throws ApiException {
        if (HttpRequestHeader.POST.equals(msg.getRequestHeader().getMethod())) {
            String body = msg.getRequestBody().toString();

            if (body.startsWith(PARAM_OBJECT_JSON + "=")) {
                handleReportObject(decodeParam(body, PARAM_OBJECT_JSON));

            } else if (body.startsWith(PARAM_EVENT_JSON)) {
                this.extension.addReportedObject(
                        new ReportedEvent(decodeParam(body, PARAM_EVENT_JSON)));
            } else if (body.startsWith(PARAM_SCRIPT_JSON)) {
                try {
                    this.extension.addZestStatement(
                            decodeParamString(body, PARAM_SCRIPT_JSON));
                } catch (Exception e) {
                    LOGGER.debug(e);
                }
            }

        } else {
            // Will be accessed via a GET as part of the browser ext initiation
            msg.setResponseBody(ApiResponseElement.OK.toJSON().toString());
        }
        return "";
    }
}
