/*
 * Copyright 2017-2020 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.discovery.vault.config;

import io.micronaut.context.annotation.BootstrapContextCompatible;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import io.micronaut.context.env.EnvironmentPropertySource;
import io.micronaut.context.env.PropertySource;
import io.micronaut.context.exceptions.ConfigurationException;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.discovery.config.ConfigurationClient;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.runtime.ApplicationConfiguration;
import io.micronaut.scheduling.TaskExecutors;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.concurrent.ExecutorService;

/**
 *  A {@link ConfigurationClient} for Vault Configuration.
 *
 *  @author thiagolocatelli
 *  @since 1.2.0
 */
@Singleton
@BootstrapContextCompatible
@Requires(beans = VaultClientConfiguration.class)
public class VaultConfigurationClient implements ConfigurationClient {

    private static final Logger LOG = LoggerFactory.getLogger(VaultConfigurationClient.class);
    private static final String DEFAULT_APPLICATION = "application";

    private final VaultConfigHttpClient<?> configHttpClient;
    private final VaultClientConfiguration vaultClientConfiguration;
    private final ApplicationConfiguration applicationConfiguration;
    private final ExecutorService executorService;

    /**
     * Default Constructor.
     *
     * @param configHttpClient          The http client
     * @param vaultClientConfiguration  Vault Client Configuration
     * @param applicationConfiguration  The application configuration
     * @param executorService           Executor Service
     */
    public VaultConfigurationClient(VaultConfigHttpClient<?> configHttpClient,
                                    VaultClientConfiguration vaultClientConfiguration,
                                    ApplicationConfiguration applicationConfiguration,
                                    @Named(TaskExecutors.IO) @Nullable ExecutorService executorService) {
        this.configHttpClient = configHttpClient;
        this.vaultClientConfiguration = vaultClientConfiguration;
        this.applicationConfiguration = applicationConfiguration;
        this.executorService = executorService;
    }

    @Override
    public Publisher<PropertySource> getPropertySources(Environment environment) {
        if (!vaultClientConfiguration.getDiscoveryConfiguration().isEnabled()) {
            return Flux.empty();
        }

        final String applicationName = applicationConfiguration.getName().orElse(null);
        final Set<String> activeNames = environment.getActiveNames();

        if (LOG.isDebugEnabled()) {
            LOG.debug("Vault server endpoint: {}, secret engine version: {}, secret-engine-name: {}",
                    vaultClientConfiguration.getUri(),
                    vaultClientConfiguration.getKvVersion(),
                    vaultClientConfiguration.getSecretEngineName());
            LOG.debug("Application name: {}, application profiles: {}", applicationName, activeNames);
        }

        List<Flux<PropertySource>> propertySources = new ArrayList<>();

        String token = vaultClientConfiguration.getToken();
        String engine = vaultClientConfiguration.getSecretEngineName();

        Scheduler scheduler = executorService != null ? Schedulers.fromExecutor(executorService) : null;

        buildVaultKeys(applicationName, activeNames).forEach((key, value) -> {
            Flux<PropertySource> propertySourceFlowable = Flux.from(
                    configHttpClient.readConfigurationValues(token, engine, value))
                    .filter(data -> !data.getSecrets().isEmpty())
                    .map(data -> PropertySource.of(value, data.getSecrets(), key))
                    .onErrorResume(t -> {
                        if (t instanceof HttpClientResponseException) {
                            if (((HttpClientResponseException) t).getStatus() == HttpStatus.NOT_FOUND) {
                                if (vaultClientConfiguration.isFailFast()) {
                                    return Flux.error(new ConfigurationException(
                                            "Could not locate PropertySource and the fail fast property is set", t));
                                }
                            }
                            return Flux.empty();
                        }
                        return Flux.error(new ConfigurationException("Error reading distributed configuration from Vault: " + t.getMessage(), t));
                    });
            if (scheduler != null) {
                propertySourceFlowable = propertySourceFlowable.subscribeOn(scheduler);
            }
            propertySources.add(propertySourceFlowable);
        });

        return Flux.merge(propertySources);
    }

    /**
     * Builds the keys used to get vault properties.
     *
     * @param applicationName The application name
     * @param environmentNames The active environments
     * @return list of vault keys
     */
    protected Map<Integer, String> buildVaultKeys(@Nullable String applicationName, Set<String> environmentNames) {
        Map<Integer, String> vaultKeys = new HashMap<>();

        int baseOrder = EnvironmentPropertySource.POSITION + 100;
        int envOrder = baseOrder + 50;

        vaultKeys.put(++baseOrder, DEFAULT_APPLICATION);
        if (applicationName != null) {
            vaultKeys.put(++baseOrder, applicationName);
        }

        for (String activeName : environmentNames) {
            vaultKeys.put(++envOrder, DEFAULT_APPLICATION + "/" + activeName);
            if (applicationName != null) {
                vaultKeys.put(++envOrder, applicationName + "/" + activeName);
            }
        }
        return vaultKeys;
    }

    @Override
    public @NonNull String getDescription() {
        return configHttpClient.getDescription();
    }
}
