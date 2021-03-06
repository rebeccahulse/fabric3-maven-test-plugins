/*
 * Fabric3
 * Copyright (c) 2009-2013 Metaform Systems
 *
 * Fabric3 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version, with the
 * following exception:
 *
 * Linking this software statically or dynamically with other
 * modules is making a combined work based on this software.
 * Thus, the terms and conditions of the GNU General Public
 * License cover the whole combination.
 *
 * As a special exception, the copyright holders of this software
 * give you permission to link this software with independent
 * modules to produce an executable, regardless of the license
 * terms of these independent modules, and to copy and distribute
 * the resulting executable under terms of your choice, provided
 * that you also meet, for each linked independent module, the
 * terms and conditions of the license of that module. An
 * independent module is a module which is not derived from or
 * based on this software. If you modify this software, you may
 * extend this exception to your version of the software, but
 * you are not obligated to do so. If you do not wish to do so,
 * delete this exception statement from your version.
 *
 * Fabric3 is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the
 * GNU General Public License along with Fabric3.
 * If not, see <http://www.gnu.org/licenses/>.
 *
 * ----------------------------------------------------
 *
 * Portions originally based on Apache Tuscany 2007
 * licensed under the Apache 2.0 license.
 *
 */
package org.fabric3.runtime.maven.itest;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.fabric3.api.host.Names;
import org.fabric3.api.host.classloader.MaskingClassLoader;
import org.fabric3.api.host.contribution.ContributionNotFoundException;
import org.fabric3.api.host.contribution.ContributionService;
import org.fabric3.api.host.contribution.ContributionSource;
import org.fabric3.api.host.contribution.FileContributionSource;
import org.fabric3.api.host.contribution.InstallException;
import org.fabric3.api.host.contribution.StoreException;
import org.fabric3.api.host.domain.DeploymentException;
import org.fabric3.api.host.domain.Domain;
import org.fabric3.api.host.runtime.InitializationException;
import org.fabric3.api.host.util.FileHelper;
import org.fabric3.plugin.Fabric3PluginException;
import org.fabric3.plugin.api.runtime.PluginRuntime;
import org.fabric3.plugin.resolver.Resolver;
import org.fabric3.plugin.runtime.PluginBootConfiguration;
import org.fabric3.plugin.runtime.PluginConstants;
import org.fabric3.plugin.util.ClassLoaderHelper;

/**
 * Instantiates a Fabric3 plugin runtime and deploys Maven modules as contributions for integration testing.
 *
 * @goal test
 * @phase integration-test
 * @execute phase="integration-test"
 */
public class Fabric3ITestMojo extends AbstractMojo {
    private static final String CLEAN = "fabric3.extensions.dependencies.cleanup";

    static {
        // This static block is used to optionally clean the temporary directory between test runs. A static block is used as the iTest plugin may
        // be instantiated multiple times during a run.
        boolean clearTmp = Boolean.valueOf(System.getProperty(CLEAN, "false"));
        if (clearTmp) {
            clearTempFiles();
        }
    }

    public static final String FABRIC3_GROUP_ID = "org.codehaus.fabric3";

    /**
     * POM
     *
     * @parameter expression="${project}"
     * @readonly
     * @required
     */
    protected MavenProject project;

    /**
     * The optional target namespace of the composite to activate.
     *
     * @parameter
     */
    public String compositeNamespace = "urn:fabric3.org";

    /**
     * The local name of the composite to activate.
     *
     * @parameter
     */
    public String compositeName = "TestComposite";

    /**
     * The project build directory.
     *
     * @parameter expression="${project.build.directory}"
     */
    public File buildDirectory;

    /**
     * Do not run if this is set to true. This usage is consistent with the surefire plugin.
     *
     * @parameter expression="${maven.test.skip}"
     */
    public boolean skip;

    /**
     * When set to true, this will ignore test failures.
     *
     * @parameter expression="${maven.test.ignoreTestFailures}"
     */
    public boolean ignoreTestFailures;

    /**
     * The directory where reports will be written.
     *
     * @parameter expression="${project.build.directory}/surefire-reports"
     */
    public File reportsDirectory;

    /**
     * Whether to trim the stack trace in the reports to just the lines within the test, or show the full trace.
     *
     * @parameter expression="${trimStackTrace}" default-value="true"
     */
    public boolean trimStackTrace;

    /**
     * The version of the runtime to use.
     *
     * @parameter
     */
    public String runtimeVersion = Names.VERSION;

    /**
     * Set of contributions that should be deployed to the runtime.
     *
     * @parameter
     */
    public Dependency[] contributions = new Dependency[0];

    /**
     * Set of runtime extension artifacts that should be deployed to the runtime.
     *
     * @parameter
     */
    public Dependency[] extensions = new Dependency[0];

    /**
     * Set of profiles for the runtime.
     *
     * @parameter
     */
    public Dependency[] profiles = new Dependency[0];

    /**
     * Libraries available to application and runtime.
     *
     * @parameter
     */
    public Dependency[] shared;

    /**
     * Allows the optional in-line specification of system configuration in the plugin configuration.
     *
     * @parameter
     */
    public String systemConfig;

    /**
     * Build output directory.
     *
     * @parameter expression="${project.build.directory}"
     * @required
     */
    public File outputDirectory;

    /**
     * Allows the optional in-line specification of an expected error
     *
     * @parameter
     */
    public String errorText;

    /**
     * JDK and system classpath packages to hide from the runtime classpath.
     *
     * @parameter
     */
    public String[] hiddenPackages = MavenHiddenPackages.getPackages();

    /**
     * @component
     */
    public RepositorySystem repositorySystem;

    /**
     * The current repository/network configuration of Maven.
     *
     * @parameter default-value="${repositorySystemSession}"
     * @readonly
     */
    public RepositorySystemSession session;

    /**
     * The project's remote repositories to use for the resolution of project dependencies.
     *
     * @parameter default-value="${project.remoteProjectRepositories}"
     * @readonly
     */
    private List<RemoteRepository> projectRepositories;

    public void execute() throws MojoExecutionException, MojoFailureException {

        if (skip || Boolean.parseBoolean(System.getProperty("maven.test.skip"))) {
            getLog().info("Skipping integration tests by user request.");
            return;
        }

        Resolver resolver = new Resolver(repositorySystem, session, projectRepositories, runtimeVersion);

        PluginBootConfiguration configuration = createBootConfiguration(resolver);

        Thread.currentThread().setContextClassLoader(configuration.getBootClassLoader());

        MavenRuntimeBooter booter = new MavenRuntimeBooter(configuration);

        try {
            PluginRuntime runtime = booter.boot();
            // load the contributions
            deployContributions(runtime, resolver);
            MavenDeployer deployer = new MavenDeployer(compositeNamespace, compositeName, buildDirectory, getLog());
            boolean continueDeployment = deployer.deploy(runtime, errorText);
            if (!continueDeployment) {
                return;
            }
            TestRunner runner = new TestRunner(reportsDirectory, trimStackTrace, getLog(), ignoreTestFailures);
            runner.executeTests(runtime);
            tryLatch(runtime);
        } catch (RuntimeException e) {
            // log unexpected errors since Maven sometimes swallows them
            getLog().error(e);
            throw e;
        } catch (Fabric3PluginException | InitializationException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        } finally {
            try {
                booter.shutdown();
            } catch (Exception e) {
                // ignore
            }
        }
    }

    /**
     * Waits on a latch component if one is configured for the test run.
     *
     * @param runtime the runtime
     */
    private void tryLatch(PluginRuntime runtime) {
        Object latchComponent = runtime.getComponent(Object.class, PluginConstants.TEST_LATCH_SERVICE);
        if (latchComponent != null) {
            Class<?> type = latchComponent.getClass();
            try {
                Method method = type.getDeclaredMethod("await");
                getLog().info("Waiting on Fabric3 runtime latch");
                method.invoke(latchComponent);
                getLog().info("Fabric3 runtime latch released");
            } catch (IllegalAccessException | InvocationTargetException | IllegalArgumentException | SecurityException | NoSuchMethodException e) {
                getLog().error("Exception attempting to wait on latch service", e);
            }
        }
    }

    /**
     * Resolves and deploys configured contributions.
     *
     * @param runtime the runtime
     * @throws MojoExecutionException if a deployment error occurs
     */
    private void deployContributions(PluginRuntime runtime, Resolver resolver) throws MojoExecutionException {
        if (contributions.length <= 0) {
            return;
        }
        try {
            ContributionService contributionService = runtime.getComponent(ContributionService.class, Names.CONTRIBUTION_SERVICE_URI);
            Domain domain = runtime.getComponent(Domain.class, Names.APPLICATION_DOMAIN_URI);
            List<ContributionSource> sources = new ArrayList<>();
            Set<Artifact> artifacts = convert(contributions);
            Set<URL> resolvedArtifacts = resolver.resolve(artifacts);
            for (URL url : resolvedArtifacts) {
                URI uri = URI.create(new File(url.getFile()).getName());
                ContributionSource source = new FileContributionSource(uri, url, -1, true);
                sources.add(source);
            }
            List<URI> uris = contributionService.store(sources);
            contributionService.install(uris);
            domain.include(uris);
        } catch (ArtifactResolutionException | InstallException | ContributionNotFoundException | DeploymentException | StoreException e) {
            throw new MojoExecutionException("Error installing contributions", e);
        }
    }

    /**
     * Recursively cleans the F3 temporary directory.
     */
    private static void clearTempFiles() {
        File f3TempDir = new File(System.getProperty("java.io.tmpdir"), ".f3");
        try {
            FileHelper.deleteDirectory(f3TempDir);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Creates the configuration to boot the Maven runtime, including resolving dependencies.
     *
     * @return the boot configuration
     * @throws MojoExecutionException if there is an error creating the configuration
     */
    private PluginBootConfiguration createBootConfiguration(Resolver resolver) throws MojoExecutionException {
        try {
            Set<Artifact> runtimeArtifacts = resolver.resolveRuntimeArtifacts();

            Set<Artifact> sharedArtifacts = convert(shared);
            Set<Artifact> hostArtifacts = resolver.resolveHostArtifacts(sharedArtifacts);

            Set<Artifact> extensionArtifacts = convert(extensions);
            extensionArtifacts.add(new DefaultArtifact(FABRIC3_GROUP_ID, "fabric3-maven-extension", "jar", runtimeVersion));

            Set<Artifact> profileArtifacts = convert(profiles);
            List<ContributionSource> runtimeExtensions = resolver.resolveRuntimeExtensions(extensionArtifacts, profileArtifacts);

            Set<URL> moduleDependencies = resolveModuleDependencies(hostArtifacts, resolver);

            ClassLoader parentClassLoader = createParentClassLoader();

            ClassLoader hostClassLoader = ClassLoaderHelper.createHostClassLoader(parentClassLoader, hostArtifacts);
            ClassLoader bootClassLoader = ClassLoaderHelper.createBootClassLoader(hostClassLoader, runtimeArtifacts);

            PluginBootConfiguration configuration = new PluginBootConfiguration();
            configuration.setBootClassLoader(bootClassLoader);
            configuration.setHostClassLoader(hostClassLoader);

            MavenDestinationRouter router = new MavenDestinationRouter(getLog());
            configuration.setRouter(router);

            configuration.setExtensions(runtimeExtensions);
            configuration.setModuleDependencies(moduleDependencies);

            configuration.setOutputDirectory(outputDirectory);
            configuration.setSystemConfig(systemConfig);
            return configuration;
        } catch (DependencyResolutionException | ArtifactResolutionException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    private ClassLoader createParentClassLoader() {
        ClassLoader parentClassLoader = getClass().getClassLoader();
        if (hiddenPackages.length > 0) {
            // mask hidden JDK and system classpath packages
            parentClassLoader = new MaskingClassLoader(parentClassLoader, hiddenPackages);
        }
        return parentClassLoader;
    }

    private Set<Artifact> convert(Dependency[] dependencies) {
        if (dependencies == null) {
            return Collections.emptySet();
        }
        Set<Artifact> artifacts = new HashSet<>(dependencies.length);
        for (Dependency dependency : dependencies) {
            Artifact artifact = convert(dependency);
            artifacts.add(artifact);
        }
        return artifacts;
    }

    private Artifact convert(Dependency dependency) {
        String groupId = dependency.getGroupId();
        String artifactId = dependency.getArtifactId();
        String classifier = dependency.getClassifier();
        String type = dependency.getType();
        String version = dependency.getVersion();
        return new DefaultArtifact(groupId, artifactId, classifier, type, version);
    }

    /**
     * Calculates module dependencies based on the set of project artifacts. Module dependencies must be visible to implementation code in a composite and
     * encompass project artifacts minus artifacts provided by the host classloader and those that are "provided scope".
     *
     * @param hostArtifacts the set of host artifacts
     * @return the set of URLs pointing to module dependencies.
     */
    public Set<URL> resolveModuleDependencies(Set<Artifact> hostArtifacts, Resolver resolver) throws MojoExecutionException {
        try {
            Set<org.eclipse.aether.graph.Dependency> projectDependencies = calculateProjectDependencies(resolver);
            Set<URL> urls = new LinkedHashSet<>();
            for (org.eclipse.aether.graph.Dependency dependency : projectDependencies) {
                String scope = dependency.getScope();
                Artifact artifact = dependency.getArtifact();
                if (hostArtifacts.contains(artifact) || "provided".equals(scope)) {
                    continue;
                }

                File pathElement = artifact.getFile();
                URL url = pathElement.toURI().toURL();
                urls.add(url);
            }
            return urls;
        } catch (MalformedURLException | DependencyResolutionException e) {
            // toURI should have encoded the URL
            throw new MojoExecutionException(e.getMessage(), e);
        }

    }

    private Set<org.eclipse.aether.graph.Dependency> calculateProjectDependencies(Resolver resolver) throws DependencyResolutionException {
        // add all declared project dependencies
        Set<org.eclipse.aether.graph.Dependency> artifacts = new HashSet<>();
        for (Dependency dependency : project.getDependencies()) {
            Set<Artifact> resolved = resolver.resolveTransitively(convert(dependency));
            for (Artifact artifact : resolved) {
                artifacts.add(new org.eclipse.aether.graph.Dependency(artifact, dependency.getScope()));
            }
        }
        // include any artifacts that have been added by other plugins (e.g. Clover see FABRICTHREE-220)
        for (org.apache.maven.artifact.Artifact dependency : project.getDependencyArtifacts()) {
            Set<Artifact> resolved = resolver.resolveTransitively(convertArtifact(dependency));
            for (Artifact artifact : resolved) {
                artifacts.add(new org.eclipse.aether.graph.Dependency(artifact, dependency.getScope()));
            }
        }
        return artifacts;
    }

    private DefaultArtifact convertArtifact(org.apache.maven.artifact.Artifact artifact) {
        String groupId = artifact.getGroupId();
        String artifactId = artifact.getArtifactId();
        String classifier = artifact.getClassifier();
        String type = artifact.getType();
        String version = artifact.getVersion();
        DefaultArtifact converted = new DefaultArtifact(groupId, artifactId, classifier, type, version);
        converted = (DefaultArtifact) converted.setFile(artifact.getFile());    // Aether creates a new instance so reassign it :-(
        return converted;
    }

}
