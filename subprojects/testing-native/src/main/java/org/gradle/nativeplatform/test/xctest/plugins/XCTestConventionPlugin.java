/*
 * Copyright 2017 the original author or authors.
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

package org.gradle.nativeplatform.test.xctest.plugins;

import com.google.common.collect.Lists;
import org.gradle.api.Incubating;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFile;
import org.gradle.api.internal.attributes.ImmutableAttributesFactory;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.language.base.plugins.LifecycleBasePlugin;
import org.gradle.language.internal.NativeComponentFactory;
import org.gradle.language.nativeplatform.internal.Dimensions;
import org.gradle.language.nativeplatform.internal.Names;
import org.gradle.language.nativeplatform.internal.toolchains.ToolChainSelector;
import org.gradle.language.swift.ProductionSwiftComponent;
import org.gradle.language.swift.SwiftApplication;
import org.gradle.language.swift.SwiftComponent;
import org.gradle.language.swift.SwiftPlatform;
import org.gradle.language.swift.internal.DefaultSwiftBinary;
import org.gradle.language.swift.plugins.SwiftBasePlugin;
import org.gradle.language.swift.tasks.SwiftCompile;
import org.gradle.language.swift.tasks.UnexportMainSymbol;
import org.gradle.model.internal.registry.ModelRegistry;
import org.gradle.nativeplatform.TargetMachine;
import org.gradle.nativeplatform.TargetMachineFactory;
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform;
import org.gradle.nativeplatform.tasks.LinkMachOBundle;
import org.gradle.nativeplatform.test.plugins.NativeTestingBasePlugin;
import org.gradle.nativeplatform.test.xctest.SwiftXCTestBinary;
import org.gradle.nativeplatform.test.xctest.SwiftXCTestBundle;
import org.gradle.nativeplatform.test.xctest.SwiftXCTestSuite;
import org.gradle.nativeplatform.test.xctest.internal.DefaultSwiftXCTestBinary;
import org.gradle.nativeplatform.test.xctest.internal.DefaultSwiftXCTestBundle;
import org.gradle.nativeplatform.test.xctest.internal.DefaultSwiftXCTestExecutable;
import org.gradle.nativeplatform.test.xctest.internal.DefaultSwiftXCTestSuite;
import org.gradle.nativeplatform.test.xctest.tasks.InstallXCTestBundle;
import org.gradle.nativeplatform.test.xctest.tasks.XCTest;
import org.gradle.nativeplatform.toolchain.NativeToolChain;
import org.gradle.nativeplatform.toolchain.internal.NativeToolChainInternal;
import org.gradle.nativeplatform.toolchain.internal.NativeToolChainRegistryInternal;
import org.gradle.nativeplatform.toolchain.internal.PlatformToolProvider;
import org.gradle.nativeplatform.toolchain.internal.xcode.MacOSSdkPlatformPathLocator;
import org.gradle.util.GUtil;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.io.File;
import java.util.Arrays;
import java.util.Set;

import static org.gradle.language.nativeplatform.internal.Dimensions.isBuildable;
import static org.gradle.language.nativeplatform.internal.Dimensions.useHostAsDefaultTargetMachine;

/**
 * A plugin that sets up the infrastructure for testing native binaries with XCTest test framework. It also adds conventions on top of it.
 *
 * @since 4.2
 */
@Incubating
public class XCTestConventionPlugin implements Plugin<Project> {
    private final MacOSSdkPlatformPathLocator sdkPlatformPathLocator;
    private final ToolChainSelector toolChainSelector;
    private final NativeComponentFactory componentFactory;
    private final ObjectFactory objectFactory;
    private final ImmutableAttributesFactory attributesFactory;
    private final TargetMachineFactory targetMachineFactory;

    @Inject
    public XCTestConventionPlugin(MacOSSdkPlatformPathLocator sdkPlatformPathLocator, ToolChainSelector toolChainSelector, NativeComponentFactory componentFactory, ObjectFactory objectFactory, ImmutableAttributesFactory attributesFactory, TargetMachineFactory targetMachineFactory) {
        this.sdkPlatformPathLocator = sdkPlatformPathLocator;
        this.toolChainSelector = toolChainSelector;
        this.componentFactory = componentFactory;
        this.objectFactory = objectFactory;
        this.attributesFactory = attributesFactory;
        this.targetMachineFactory = targetMachineFactory;
    }

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(SwiftBasePlugin.class);
        project.getPluginManager().apply(NativeTestingBasePlugin.class);

        final ProviderFactory providers = project.getProviders();

        // Create test suite component
        final DefaultSwiftXCTestSuite testComponent = createTestSuite(project);

        testComponent.getTargetMachines().convention(useHostAsDefaultTargetMachine(targetMachineFactory));
        final String mainComponentName = "main";

        project.getComponents().withType(ProductionSwiftComponent.class, component -> {
            if (mainComponentName.equals(component.getName())) {
                testComponent.getTargetMachines().convention(component.getTargetMachines());
                testComponent.getTestedComponent().set(component);
            }
        });

        testComponent.getTestBinary().convention(project.provider(() -> {
            return testComponent.getBinaries().get().stream()
                    .filter(SwiftXCTestBinary.class::isInstance)
                    .map(SwiftXCTestBinary.class::cast)
                    .findFirst()
                    .orElse(null);
        }));

        testComponent.getBinaries().whenElementKnown(DefaultSwiftXCTestBinary.class, binary -> {
            // Create test suite test task
            TaskProvider<XCTest> testingTask = createTestingTask(project);
            binary.getRunTask().set(testingTask);

            // Configure tasks
            testingTask.configure(task -> {
                task.getTestInstallDirectory().set(binary.getInstallDirectory());
                task.getRunScriptFile().set(binary.getRunScriptFile());
                task.getWorkingDirectory().set(binary.getInstallDirectory());
            });


            configureTestSuiteBuildingTasks(project, binary);
            configureTestSuiteWithTestedComponentWhenAvailable(project, testComponent, binary);
        });

        project.afterEvaluate(p -> {
            final ProductionSwiftComponent mainComponent = project.getComponents().withType(ProductionSwiftComponent.class).findByName(mainComponentName);

            testComponent.getTargetMachines().finalizeValue();
            Set<TargetMachine> targetMachines = testComponent.getTargetMachines().get();
            validateTargetMachines(targetMachines, mainComponent != null ? mainComponent.getTargetMachines().get() : null);

            Dimensions.unitTestVariants(testComponent.getModule(), testComponent.getTargetMachines(), objectFactory, attributesFactory,
                    providers.provider(() -> project.getGroup().toString()), providers.provider(() -> project.getVersion().toString()),
                    variantIdentity -> {
                        if (isBuildable(variantIdentity)) {
                            ToolChainSelector.Result<SwiftPlatform> result = toolChainSelector.select(SwiftPlatform.class, variantIdentity.getTargetMachine());

                            // Create test suite executable
                            if (result.getTargetPlatform().getOperatingSystemFamily().isMacOs()) {
                                testComponent.addBundle(variantIdentity, result.getTargetPlatform(), result.getToolChain(), result.getPlatformToolProvider());

                            } else {
                                testComponent.addExecutable(variantIdentity, result.getTargetPlatform(), result.getToolChain(), result.getPlatformToolProvider());
                            }
                        }
                    });

            testComponent.getBinaries().realizeNow();
        });
    }

    private void configureTestSuiteBuildingTasks(final Project project, final DefaultSwiftXCTestBinary binary) {
        if (binary instanceof SwiftXCTestBundle) {
            TaskContainer tasks = project.getTasks();
            final Names names = binary.getNames();

            // TODO - creating a bundle should be done by some general purpose plugin

            // TODO - make this lazy
            final DefaultNativePlatform currentPlatform = new DefaultNativePlatform("current");
            final ModelRegistry modelRegistry = ((ProjectInternal) project).getModelRegistry();
            final NativeToolChain toolChain = modelRegistry.realize("toolChains", NativeToolChainRegistryInternal.class).getForPlatform(currentPlatform);

            // Platform specific arguments
            // TODO: Need to lazily configure compile task
            // TODO: Ultimately, this should be some kind of 3rd party dependency that's visible to dependency management.
            SwiftCompile compile = binary.getCompileTask().get();
            compile.getCompilerArgs().addAll(project.provider(() -> {
                File frameworkDir = new File(sdkPlatformPathLocator.find(), "Developer/Library/Frameworks");
                return Arrays.asList("-parse-as-library", "-F" + frameworkDir.getAbsolutePath());
            }));

            // Add a link task
            final TaskProvider<LinkMachOBundle> link = tasks.register(names.getTaskName("link"), LinkMachOBundle.class, task -> {
                task.getLinkerArgs().set(project.provider(() -> {
                    File frameworkDir = new File(sdkPlatformPathLocator.find(), "Developer/Library/Frameworks");
                    return Lists.newArrayList("-F" + frameworkDir.getAbsolutePath(), "-framework", "XCTest", "-Xlinker", "-rpath", "-Xlinker", "@executable_path/../Frameworks", "-Xlinker", "-rpath", "-Xlinker", "@loader_path/../Frameworks");
                }));

                task.source(binary.getObjects());
                task.lib(binary.getLinkLibraries());
                final PlatformToolProvider toolProvider = ((NativeToolChainInternal) toolChain).select(currentPlatform);

                Provider<RegularFile> exeLocation = project.getLayout().getBuildDirectory().file(binary.getBaseName().map(baseName -> toolProvider.getExecutableName("exe/" + names.getDirName() + baseName)));
                task.getLinkedFile().set(exeLocation);
                task.getTargetPlatform().set(currentPlatform);
                task.getToolChain().set(toolChain);
                task.getDebuggable().set(binary.isDebuggable());
            });


            final TaskProvider<InstallXCTestBundle> install = tasks.register(names.getTaskName("install"), InstallXCTestBundle.class, task -> {
                task.getBundleBinaryFile().set(link.get().getLinkedFile());
                task.getInstallDirectory().set(project.getLayout().getBuildDirectory().dir("install/" + names.getDirName()));
            });
            binary.getInstallDirectory().set(install.flatMap(task -> task.getInstallDirectory()));
            binary.getExecutableFile().set(link.flatMap(task -> task.getLinkedFile()));

            DefaultSwiftXCTestBundle bundle = (DefaultSwiftXCTestBundle) binary;
            bundle.getLinkTask().set(link);
            bundle.getRunScriptFile().set(install.flatMap(task -> task.getRunScriptFile()));
        } else {
            DefaultSwiftXCTestExecutable executable = (DefaultSwiftXCTestExecutable) binary;
            executable.getRunScriptFile().set(executable.getInstallTask().flatMap(task -> task.getRunScriptFile()));
        }
    }

    private TaskProvider<XCTest> createTestingTask(Project project) {
        return project.getTasks().register("xcTest", XCTest.class, task -> {
            task.setGroup(LifecycleBasePlugin.VERIFICATION_GROUP);
            task.setDescription("Executes XCTest suites");
        });
    }

    private DefaultSwiftXCTestSuite createTestSuite(final Project project) {
        // TODO - Reuse logic from Swift*Plugin
        // TODO - component name and extension name aren't the same
        // TODO - should use `src/xctest/swift` as the convention?
        // Add the test suite and extension
        DefaultSwiftXCTestSuite testSuite = componentFactory.newInstance(SwiftXCTestSuite.class, DefaultSwiftXCTestSuite.class, "test");

        project.getExtensions().add(SwiftXCTestSuite.class, "xctest", testSuite);
        project.getComponents().add(testSuite);

        // Setup component
        testSuite.getModule().set(GUtil.toCamelCase(project.getName() + "Test"));

        return testSuite;
    }

    private void configureTestSuiteWithTestedComponentWhenAvailable(final Project project, final DefaultSwiftXCTestSuite testSuite, final DefaultSwiftXCTestBinary testExecutable) {
        SwiftComponent target = testSuite.getTestedComponent().getOrNull();
        if (!(target instanceof ProductionSwiftComponent)) {
            return;
        }
        final ProductionSwiftComponent testedComponent = (ProductionSwiftComponent) target;

        final TaskContainer tasks = project.getTasks();
        testedComponent.getBinaries().whenElementFinalized(testedBinary -> {
                if (testedBinary != testedComponent.getDevelopmentBinary().get()) {
                    return;
                }

                // If nothing was configured for the test suite source compatibility, use the tested component one.
                if (testSuite.getSourceCompatibility().getOrNull() == null) {
                    testExecutable.getSourceCompatibility().set(testedBinary.getSourceCompatibility());
                }

                // Setup the dependency on the main binary
                // This should all be replaced by a single dependency that points at some "testable" variants of the main binary

                // Inherit implementation dependencies
                testExecutable.getImplementationDependencies().extendsFrom(((DefaultSwiftBinary) testedBinary).getImplementationDependencies());

                // Configure test binary to compile against binary under test
                Dependency compileDependency = project.getDependencies().create(project.files(testedBinary.getModuleFile()));
                testExecutable.getImportPathConfiguration().getDependencies().add(compileDependency);

                // Configure test binary to link against tested component compiled objects
                ConfigurableFileCollection testableObjects = project.files();
                if (testedComponent instanceof SwiftApplication) {
                    TaskProvider<UnexportMainSymbol> unexportMainSymbol = tasks.register("relocateMainForTest", UnexportMainSymbol.class, task -> {
                            task.getOutputDirectory().set(project.getLayout().getBuildDirectory().dir("obj/main/for-test"));
                            task.getObjects().from(testedBinary.getObjects());
                    });
                    // TODO: builtBy unnecessary?
                    testableObjects.builtBy(unexportMainSymbol);
                    testableObjects.from(unexportMainSymbol.map(task -> task.getRelocatedObjects()));
                } else {
                    testableObjects.from(testedBinary.getObjects());
                }
                Dependency linkDependency = project.getDependencies().create(testableObjects);
                testExecutable.getLinkConfiguration().getDependencies().add(linkDependency);
        });
    }

    private void validateTargetMachines(Set<TargetMachine> testTargetMachines, @Nullable Set<TargetMachine> mainTargetMachines) {
        if (testTargetMachines.isEmpty()) {
            throw new IllegalArgumentException("A target machine needs to be specified for the unit test.");
        }

        if (mainTargetMachines != null) {
            for (TargetMachine machine : testTargetMachines) {
                if (!mainTargetMachines.contains(machine)) {
                    throw new IllegalArgumentException("The target machine " + machine.toString() + " was specified for the unit test, but this target machine was not specified on the main component.");
                }
            }
        }
    }
}
