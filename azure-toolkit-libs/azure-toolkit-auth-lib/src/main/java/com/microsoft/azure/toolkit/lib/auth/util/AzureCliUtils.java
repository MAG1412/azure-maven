/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.toolkit.lib.auth.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.microsoft.azure.toolkit.lib.Azure;
import com.microsoft.azure.toolkit.lib.AzureConfiguration;
import com.microsoft.azure.toolkit.lib.auth.exception.AzureToolkitAuthenticationException;
import com.microsoft.azure.toolkit.lib.auth.model.AzureCliSubscription;
import com.microsoft.azure.toolkit.lib.common.utils.CommandUtils;
import com.microsoft.azure.toolkit.lib.common.utils.JsonUtils;
import com.vdurmont.semver4j.Semver;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AzureCliUtils {
    private static final String MIN_VERSION = "2.11.0";

    public static void ensureMinimumCliVersion() {
        try {
            final JsonObject result = JsonUtils.getGson().fromJson(AzureCliUtils.executeAzureCli("az version --output json"), JsonObject.class);
            final String cliVersion = result.get("azure-cli").getAsString();
            // we require at least azure cli version 2.11.0
            if (compareWithMinimVersion(cliVersion) < 0) {
                throw new AzureToolkitAuthenticationException(String.format("your Azure Cli version '%s' is too old, " +
                        "you need to upgrade your CLI with 'az upgrade'.", cliVersion));
            }
        } catch (NullPointerException | NumberFormatException ex) {
            throw new AzureToolkitAuthenticationException(
                    String.format("Azure Cli is not ready, " +
                            "please make sure your Azure Cli is installed and signed-in, the detailed error is : %s", ex.getMessage()));
        }
    }

    @Nonnull
    public static List<AzureCliSubscription> listSubscriptions() {
        final String jsonString = executeAzureCli("az account list --output json");
        final JsonArray result = JsonUtils.getGson().fromJson(jsonString, JsonArray.class);
        final List<AzureCliSubscription> list = new ArrayList<>();
        if (result != null) {
            result.forEach(j -> {
                JsonObject accountObject = j.getAsJsonObject();
                if (!accountObject.has("id")) {
                    return;
                }
                // TODO: use utility to handle the json mapping
                String tenantId = accountObject.get("tenantId").getAsString();
                String subscriptionId = accountObject.get("id").getAsString();
                String subscriptionName = accountObject.get("name").getAsString();
                String state = accountObject.get("state").getAsString();
                String cloud = accountObject.get("cloudName").getAsString();
                String email = accountObject.get("user").getAsJsonObject().get("name").getAsString();

                if (StringUtils.equals(state, "Enabled") && StringUtils.isNoneBlank(subscriptionId, subscriptionName)) {
                    AzureCliSubscription entity = new AzureCliSubscription();
                    entity.setId(subscriptionId);
                    entity.setName(subscriptionName);
                    entity.setSelected(accountObject.get("isDefault").getAsBoolean());
                    entity.setTenantId(tenantId);
                    entity.setEmail(email);
                    entity.setEnvironment(AzureEnvironmentUtils.stringToAzureEnvironment(cloud));
                    list.add(entity);
                }
            });
            return list;
        }

        throw new AzureToolkitAuthenticationException(
                "list subscriptions by command `az account list` failed, please make sure you have signed in Azure Cli using `az login`");
    }

    @Nonnull
    public static String executeAzureCli(@Nonnull String command) {
        try {
            final AzureConfiguration config = Azure.az().config();
            Map<String, String> env = new HashMap<>();
            if (StringUtils.isNotBlank(config.getProxySource())) {
                String proxyAuthPrefix = StringUtils.EMPTY;
                if (StringUtils.isNoneBlank(config.getProxyUsername(), config.getProxyPassword())) {
                    proxyAuthPrefix = config.getProxyUsername() + ":" + config.getProxyPassword() + "@";
                }
                String proxyStr = String.format("http://%s%s:%s", proxyAuthPrefix, config.getHttpProxyHost(), config.getHttpProxyPort());
                env.put("HTTPS_PROXY", proxyStr);
                env.put("HTTP_PROXY", proxyStr);
            }
            return CommandUtils.exec(command, env);
        } catch (IOException e) {
            throw new AzureToolkitAuthenticationException(
                    String.format("execute Azure Cli command '%s' failed due to error: %s.", command, e.getMessage()));
        }
    }

    private static int compareWithMinimVersion(String version) {
        final Semver current = new Semver(version);
        return current.compareTo(new Semver(MIN_VERSION));
    }
}
