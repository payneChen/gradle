/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.jvm.toolchain.internal;

import org.gradle.api.GradleException;
import org.gradle.api.Transformer;
import org.gradle.api.internal.provider.DefaultProvider;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.internal.deprecation.DeprecationLogger;
import org.gradle.internal.jvm.Jvm;
import org.gradle.jvm.toolchain.JavaToolchainSpec;
import org.gradle.jvm.toolchain.install.internal.DefaultJavaToolchainProvisioningService;
import org.gradle.jvm.toolchain.install.internal.JavaToolchainProvisioningService;

import javax.inject.Inject;
import java.io.File;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public class JavaToolchainQueryService {

    private final JavaInstallationRegistry registry;
    private final JavaToolchainFactory toolchainFactory;
    private final JavaToolchainProvisioningService installService;
    private final Provider<Boolean> detectEnabled;
    private final Provider<Boolean> downloadEnabled;
    private final Map<JavaToolchainSpec, JavaToolchain> matchingToolchains;

    @Inject
    public JavaToolchainQueryService(
        JavaInstallationRegistry registry,
        JavaToolchainFactory toolchainFactory,
        JavaToolchainProvisioningService provisioningService,
        ProviderFactory factory
    ) {
        this.registry = registry;
        this.toolchainFactory = toolchainFactory;
        this.installService = provisioningService;
        this.detectEnabled = factory.gradleProperty(AutoDetectingInstallationSupplier.AUTO_DETECT).map(Boolean::parseBoolean);
        this.downloadEnabled = factory.gradleProperty(DefaultJavaToolchainProvisioningService.AUTO_DOWNLOAD).map(Boolean::parseBoolean);
        this.matchingToolchains = new ConcurrentHashMap<>();
    }

    <T> Provider<T> toolFor(JavaToolchainSpec spec, Transformer<T, JavaToolchain> toolFunction) {
        return findMatchingToolchain(spec).map(toolFunction);
    }

    Provider<JavaToolchain> findMatchingToolchain(JavaToolchainSpec filter) {
        JavaToolchainSpecInternal filterInternal = (JavaToolchainSpecInternal) filter;
        if (!filterInternal.isValid()) {
            DeprecationLogger.deprecate("Using toolchain specifications without setting a language version")
                .withAdvice("Consider configuring the language version.")
                .willBecomeAnErrorInGradle8()
                .withUpgradeGuideSection(7, "invalid_toolchain_specification_deprecation")
                .nagUser();
        }

        return new DefaultProvider<>(() -> {
            if (!filterInternal.isConfigured()) {
                return null;
            }

            return matchingToolchains.computeIfAbsent(filterInternal, this::query);
        });
    }

    private JavaToolchain query(JavaToolchainSpec filter) {
        if (filter instanceof CurrentJvmToolchainSpec) {
            return asToolchain(new InstallationLocation(Jvm.current().getJavaHome(), "current JVM"), filter).get();
        }
        if (filter instanceof SpecificInstallationToolchainSpec) {
            return asToolchain(new InstallationLocation(((SpecificInstallationToolchainSpec) filter).getJavaHome(), "specific installation"), filter).get();
        }

        return registry.listInstallations().stream()
            .map(javaHome -> asToolchain(javaHome, filter))
            .filter(Optional::isPresent)
            .map(Optional::get)
            .filter(new ToolchainMatcher(filter))
            .min(new JavaToolchainComparator())
            .orElseGet(() -> downloadToolchain(filter));
    }

    private JavaToolchain downloadToolchain(JavaToolchainSpec spec) {
        final Optional<File> installation = installService.tryInstall(spec);
        final Optional<JavaToolchain> toolchain = installation
            .map(home -> asToolchain(new InstallationLocation(home, "provisioned toolchain"), spec))
            .orElseThrow(noToolchainAvailable(spec));
        return toolchain.orElseThrow(provisionedToolchainIsInvalid(installation::get));
    }

    private Supplier<GradleException> noToolchainAvailable(JavaToolchainSpec spec) {
        return () -> new NoToolchainAvailableException(spec, detectEnabled.getOrElse(true), downloadEnabled.getOrElse(true));
    }

    private Supplier<GradleException> provisionedToolchainIsInvalid(Supplier<File> javaHome) {
        return () -> new GradleException("Provisioned toolchain '" + javaHome.get() + "' could not be probed.");
    }

    private Optional<JavaToolchain> asToolchain(InstallationLocation javaHome, JavaToolchainSpec spec) {
        return toolchainFactory.newInstance(javaHome, new JavaToolchainInput(spec));
    }
}
