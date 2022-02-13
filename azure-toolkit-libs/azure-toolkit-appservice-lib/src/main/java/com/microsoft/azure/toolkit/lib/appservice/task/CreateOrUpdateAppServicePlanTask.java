/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.toolkit.lib.appservice.task;

import com.microsoft.azure.toolkit.lib.Azure;
import com.microsoft.azure.toolkit.lib.appservice.AzureAppService;
import com.microsoft.azure.toolkit.lib.appservice.config.AppServicePlanConfig;
import com.microsoft.azure.toolkit.lib.appservice.service.impl.AppServicePlan;
import com.microsoft.azure.toolkit.lib.common.messager.AzureMessager;
import com.microsoft.azure.toolkit.lib.common.operation.AzureOperation;
import com.microsoft.azure.toolkit.lib.common.task.AzureTask;
import com.microsoft.azure.toolkit.lib.common.telemetry.AzureTelemetry;
import com.microsoft.azure.toolkit.lib.common.validator.SchemaValidator;
import com.microsoft.azure.toolkit.lib.resource.task.CreateResourceGroupTask;
import lombok.AllArgsConstructor;

import java.util.Objects;

@AllArgsConstructor
public class CreateOrUpdateAppServicePlanTask extends AzureTask<AppServicePlan> {
    private static final String CREATE_APP_SERVICE_PLAN = "Creating app service plan %s...";
    private static final String CREATE_APP_SERVICE_PLAN_DONE = "Successfully created app service plan %s.";
    private static final String CREATE_NEW_APP_SERVICE_PLAN = "createNewAppServicePlan";
    private AppServicePlanConfig config;

    @AzureOperation(name = "appservice.create_update_plan.plan", params = {"this.config.servicePlanName()"}, type = AzureOperation.Type.SERVICE)
    public AppServicePlan execute() {
        SchemaValidator.getInstance().validateAndThrow("appservice/AppServicePlan", config);
        final AzureAppService az = Azure.az(AzureAppService.class).subscription(config.subscriptionId());
        final AppServicePlan appServicePlan = az.appServicePlan(config.servicePlanResourceGroup(), config.servicePlanName());
        final String servicePlanName = config.servicePlanName();
        if (!appServicePlan.exists()) {
            SchemaValidator.getInstance().validateAndThrow("appservice/CreateAppServicePlan", config);
            AzureMessager.getMessager().info(String.format(CREATE_APP_SERVICE_PLAN, servicePlanName));
            AzureTelemetry.getActionContext().setProperty(CREATE_NEW_APP_SERVICE_PLAN, String.valueOf(true));
            new CreateResourceGroupTask(this.config.subscriptionId(), config.servicePlanResourceGroup(), config.region()).execute();
            appServicePlan.create()
                .withName(servicePlanName)
                .withResourceGroup(config.servicePlanResourceGroup())
                .withPricingTier(config.pricingTier())
                .withRegion(config.region())
                .withOperatingSystem(config.os())
                .commit();
            AzureMessager.getMessager().info(String.format(CREATE_APP_SERVICE_PLAN_DONE, appServicePlan.name()));
        } else {
            if (config.region() != null && !Objects.equals(config.region(), appServicePlan.getRegion())) {
                AzureMessager.getMessager().warning(String.format("Skip region update for existing service plan '%s' since it is not allowed.",
                    appServicePlan.name()));
            }
            if (config.pricingTier() != null && !Objects.equals(config.pricingTier(), appServicePlan.getPricingTier())) {
                // apply pricing tier to service plan
                appServicePlan.update().withPricingTier(config.pricingTier()).commit();
            }
        }

        return appServicePlan;
    }
}
