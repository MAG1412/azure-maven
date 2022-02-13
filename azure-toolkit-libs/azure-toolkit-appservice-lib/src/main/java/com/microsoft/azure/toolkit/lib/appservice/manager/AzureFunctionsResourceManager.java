/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.toolkit.lib.appservice.manager;

import com.azure.core.annotation.BodyParam;
import com.azure.core.annotation.Delete;
import com.azure.core.annotation.Get;
import com.azure.core.annotation.Headers;
import com.azure.core.annotation.Host;
import com.azure.core.annotation.HostParam;
import com.azure.core.annotation.PathParam;
import com.azure.core.annotation.Put;
import com.azure.core.annotation.ServiceInterface;
import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpPipeline;
import com.azure.core.http.HttpPipelineBuilder;
import com.azure.core.http.policy.AddHeadersPolicy;
import com.azure.core.http.policy.HttpPipelinePolicy;
import com.azure.core.http.rest.Response;
import com.azure.core.http.rest.RestProxy;
import com.azure.core.http.rest.StreamResponse;
import com.azure.core.management.serializer.SerializerFactory;
import com.azure.resourcemanager.appservice.models.FunctionApp;
import com.azure.resourcemanager.appservice.models.FunctionDeploymentSlot;
import com.azure.resourcemanager.appservice.models.WebAppBase;
import com.azure.resourcemanager.resources.fluentcore.policy.AuthenticationPolicy;
import com.azure.resourcemanager.resources.fluentcore.policy.AuxiliaryAuthenticationPolicy;
import com.azure.resourcemanager.resources.fluentcore.policy.ProviderRegistrationPolicy;
import com.microsoft.azure.toolkit.lib.appservice.model.AppServiceFile;
import com.microsoft.azure.toolkit.lib.appservice.model.OperatingSystem;
import com.microsoft.azure.toolkit.lib.appservice.service.IFileClient;
import com.microsoft.azure.toolkit.lib.appservice.service.impl.FunctionAppBase;
import com.microsoft.azure.toolkit.lib.appservice.utils.Utils;
import com.microsoft.azure.toolkit.lib.common.exception.AzureToolkitRuntimeException;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.annotation.Nonnull;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class AzureFunctionsResourceManager implements IFileClient {
    private static final String LINUX_ROOT = "home";

    private final String host;
    private final FunctionsService functionsService;
    private final FunctionAppBase appService;

    private AzureFunctionsResourceManager(FunctionsService functionsService, FunctionAppBase appService) {
        this.appService = appService;
        this.functionsService = functionsService;
        this.host = String.format("https://%s", appService.hostName());
    }

    public static AzureFunctionsResourceManager getClient(@Nonnull WebAppBase functionApp, @Nonnull FunctionAppBase appService) {
        // refers : https://github.com/Azure/azure-sdk-for-java/blob/master/sdk/resourcemanager/azure-resourcemanager-appservice/src/main/java/
        // com/azure/resourcemanager/appservice/implementation/KuduClient.java
        if (!(functionApp instanceof FunctionApp || functionApp instanceof FunctionDeploymentSlot)) {
            throw new AzureToolkitRuntimeException("Functions resource manager only applies to Azure Functions");
        }
        final List<HttpPipelinePolicy> policies = Utils.getPolicyFromPipeline(functionApp.manager().httpPipeline(), policy ->
                !(policy instanceof AuthenticationPolicy || policy instanceof ProviderRegistrationPolicy || policy instanceof AuxiliaryAuthenticationPolicy));
        policies.add(new AddHeadersPolicy(new HttpHeaders(Collections.singletonMap("x-functions-key", appService.getMasterKey()))));

        final HttpPipeline httpPipeline = new HttpPipelineBuilder()
                .policies(policies.toArray(new HttpPipelinePolicy[0]))
                .httpClient(functionApp.manager().httpPipeline().getHttpClient())
                .build();
        final FunctionsService functionsService = RestProxy.create(FunctionsService.class, httpPipeline,
                SerializerFactory.createDefaultManagementSerializerAdapter());
        return new AzureFunctionsResourceManager(functionsService, appService);
    }

    public Flux<ByteBuffer> getFileContent(final String path) {
        return this.functionsService.getFileContent(host, getFixedPath(path)).flatMapMany(StreamResponse::getValue);
    }

    public List<? extends AppServiceFile> getFilesInDirectory(String dir) {
        // this file is generated by kudu itself, should not be visible to user.
        return this.functionsService.getFilesInDirectory(host, getFixedPath(dir)).block().getValue().stream()
                .filter(file -> !"text/xml".equals(file.getMime()) || !file.getName().contains("LogFiles-kudu-trace_pending.xml"))
                .map(file -> file.withApp(appService).withPath(Paths.get(dir, file.getName()).toString()))
                .collect(Collectors.toList());
    }

    public AppServiceFile getFileByPath(String path) {
        final File file = new File(path);
        final List<? extends AppServiceFile> result = getFilesInDirectory(getFixedPath(file.getParent()));
        return result.stream()
                .filter(appServiceFile -> StringUtils.equals(file.getName(), appServiceFile.getName()))
                .findFirst()
                .orElse(null);
    }

    public void uploadFileToPath(String content, String path) {
        this.functionsService.saveFile(host, getFixedPath(path), content).block();
    }

    public void createDirectory(String path) {
        this.functionsService.createDirectory(host, getFixedPath(path)).block();
    }

    public void deleteFile(String path) {
        this.functionsService.deleteFile(host, getFixedPath(path)).block();
    }

    private String getFixedPath(String originPath) {
        return appService.getRuntime().getOperatingSystem() == OperatingSystem.WINDOWS || StringUtils.startsWithIgnoreCase(originPath, LINUX_ROOT) ?
                originPath : Paths.get(LINUX_ROOT, originPath).toString();
    }

    @Host("{$host}")
    @ServiceInterface(name = "FunctionHost")
    private interface FunctionsService {
        @Headers({
            "Content-Type: application/json; charset=utf-8"
        })
        @Get("admin/vfs/{path}")
        Mono<StreamResponse> getFileContent(@HostParam("$host") String host, @PathParam("path") String path);

        @Headers({
            "Content-Type: application/json; charset=utf-8"
        })
        @Get("admin/vfs/{path}/")
        Mono<Response<List<AppServiceFile>>> getFilesInDirectory(@HostParam("$host") String host, @PathParam("path") String path);

        @Headers({
            "Content-Type: application/octet-stream; charset=utf-8",
            "If-Match: *"
        })
        @Put("admin/vfs/{path}")
        Mono<Void> saveFile(@HostParam("$host") String host, @PathParam("path") String path, @BodyParam("application/octet-stream") String content);

        @Headers({
            "Content-Type: application/json; charset=utf-8"
        })
        @Put("admin/vfs/{path}/")
        Mono<Void> createDirectory(@HostParam("$host") String host, @PathParam("path") String path);

        @Headers({
            "Content-Type: application/json; charset=utf-8",
            "If-Match: *"
        })
        @Delete("admin/vfs/{path}")
        Mono<Void> deleteFile(@HostParam("$host") String host, @PathParam("path") String path);
    }
}
