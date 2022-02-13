/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.toolkit.lib.compute.vm;

import com.azure.resourcemanager.compute.models.OSProfile;
import com.azure.resourcemanager.compute.models.PowerState;
import com.azure.resourcemanager.network.models.PublicIpAddress;
import com.microsoft.azure.toolkit.lib.common.entity.IAzureBaseResource;
import com.microsoft.azure.toolkit.lib.common.entity.IAzureModule;
import com.microsoft.azure.toolkit.lib.common.entity.Removable;
import com.microsoft.azure.toolkit.lib.common.event.AzureOperationEvent;
import com.microsoft.azure.toolkit.lib.common.model.Region;
import com.microsoft.azure.toolkit.lib.compute.AbstractAzureResource;
import com.microsoft.azure.toolkit.lib.compute.vm.model.OperatingSystem;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;
import java.util.Optional;

@NoArgsConstructor
public class VirtualMachine extends AbstractAzureResource<com.azure.resourcemanager.compute.models.VirtualMachine, IAzureBaseResource>
    implements AzureOperationEvent.Source<VirtualMachine>, Removable {

    protected AzureVirtualMachine module;

    public VirtualMachine(@Nonnull String id, @Nullable AzureVirtualMachine module) {
        super(id);
        this.module = module;
    }

    public VirtualMachine(@Nonnull com.azure.resourcemanager.compute.models.VirtualMachine resource, @Nonnull AzureVirtualMachine module) {
        super(resource);
        this.module = module;
    }

    @Nonnull
    @Override
    public IAzureModule<? extends AbstractAzureResource<com.azure.resourcemanager.compute.models.VirtualMachine, IAzureBaseResource>,
        ? extends IAzureBaseResource> module() {
        return module;
    }

    public void start() {
        this.status(Status.PENDING);
        remote().start();
        this.refreshStatus();
    }

    public OperatingSystem getOperatingSystem() {
        return Optional.ofNullable(remote().osProfile()).map(OSProfile::windowsConfiguration)
                .map(ignore -> OperatingSystem.Windows).orElse(OperatingSystem.Linux);
    }

    public void stop() {
        this.status(Status.PENDING);
        remote().powerOff();
        this.refreshStatus();
    }

    public void restart() {
        this.status(Status.PENDING);
        remote().restart();
        this.refreshStatus();
    }

    public Region getRegion() {
        return Region.fromName(remote().regionName());
    }

    public boolean isSshEnabled() {
        // TODO: @wangmi check if ssh is enabled, possible solution: INBOUND/TCP/22
        // return remote().getPrimaryNetworkInterface().getNetworkSecurityGroup().securityRules().entrySet().stream()
        //    .anyMatch(e -> SecurityRuleProtocol.TCP.equals(e.getValue().protocol()) &&
        //        SecurityRuleDirection.INBOUND.equals(e.getValue().direction()) &&
        //        "22".equals(e.getValue().destinationPortRange()) &&
        //        "*".equals(e.getValue().destinationPortRange()));
        return Objects.nonNull(this.getHostIp());
    }

    @Nullable
    public String getHostIp() {
        return Optional.ofNullable(remote().getPrimaryPublicIPAddress()).map(PublicIpAddress::ipAddress).orElse(null);
    }

    public String getAdminUserName() {
        return remote().innerModel().osProfile().adminUsername();
    }

    public boolean isPasswordAuthenticationDisabled() {
        final OSProfile profile = remote().innerModel().osProfile();
        if (Objects.nonNull(profile.linuxConfiguration())) {
            return profile.linuxConfiguration().disablePasswordAuthentication();
        }
        return false;
    }

    @Override
    protected String loadStatus() {
        final String powerState = Optional.ofNullable(remote().powerState()).map(Objects::toString).orElse(StringUtils.EMPTY);
        if (StringUtils.equalsIgnoreCase(powerState, PowerState.RUNNING.toString())) {
            return Status.RUNNING;
        } else if (StringUtils.equalsAnyIgnoreCase(powerState, PowerState.DEALLOCATING.toString(), PowerState.STOPPING.toString(),
            PowerState.STARTING.toString())) {
            return Status.PENDING;
        } else if (StringUtils.equalsAnyIgnoreCase(powerState, PowerState.STOPPED.toString(), PowerState.DEALLOCATED.toString())) {
            return Status.STOPPED;
        } else {
            return Status.UNKNOWN;
        }
    }

    @Nullable
    @Override
    protected com.azure.resourcemanager.compute.models.VirtualMachine loadRemote() {
        return module.getVirtualMachinesManager(subscriptionId).getByResourceGroup(resourceGroup, name);
    }

    @Override
    public void remove() {
        if (this.exists()) {
            this.status(Status.PENDING);
            this.remote().manager().virtualMachines().deleteById(id());
            this.module.refresh();
        }
    }
}
