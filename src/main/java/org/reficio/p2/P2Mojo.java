/**
 * Copyright (c) 2012 Reficio (TM) - Reestablish your software! All Rights Reserved.
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.reficio.p2;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.AbstractMojoExecutionException;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.PlexusConstants;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.codehaus.plexus.context.Context;
import org.codehaus.plexus.context.ContextException;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.Contextualizable;
import org.eclipse.sisu.equinox.launching.internal.P2ApplicationLauncher;
import org.reficio.p2.bundler.ArtifactBundler;
import org.reficio.p2.bundler.ArtifactBundlerInstructions;
import org.reficio.p2.bundler.ArtifactBundlerRequest;
import org.reficio.p2.bundler.impl.AquteBundler;
import org.reficio.p2.logger.Logger;
import org.reficio.p2.publisher.BundlePublisher;
import org.reficio.p2.publisher.CategoryPublisher;
import org.reficio.p2.resolver.eclipse.EclipseResolutionRequest;
import org.reficio.p2.resolver.eclipse.impl.DefaultEclipseResolver;
import org.reficio.p2.resolver.maven.Artifact;
import org.reficio.p2.resolver.maven.ArtifactResolutionRequest;
import org.reficio.p2.resolver.maven.ArtifactResolutionResult;
import org.reficio.p2.resolver.maven.ArtifactResolver;
import org.reficio.p2.resolver.maven.ResolvedArtifact;
import org.reficio.p2.resolver.maven.impl.AetherResolver;
import org.reficio.p2.utils.JarUtils;

import com.google.common.base.Preconditions;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;

import edu.emory.mathcs.backport.java.util.Arrays;

/**
 * Main plugin class
 *
 * @author Tom Bujok (tom.bujok@gmail.com)<br>
 *         Reficio (TM) - Reestablish your software!<br>
 *         http://www.reficio.org
 * @since 1.0.0
 */
@Mojo(
        name = "site",
        defaultPhase = LifecyclePhase.COMPILE,
        requiresDependencyResolution = ResolutionScope.RUNTIME,
        requiresDependencyCollection = ResolutionScope.RUNTIME
)
public class P2Mojo extends AbstractMojo implements Contextualizable {

    private static final String BUNDLES_TOP_FOLDER = "/source";
    private static final String FEATURES_DESTINATION_FOLDER = BUNDLES_TOP_FOLDER + "/features";
    private static final String BUNDLES_DESTINATION_FOLDER = BUNDLES_TOP_FOLDER + "/plugins";
    private static final String DEFAULT_CATEGORY_FILE = "category.xml";
    private static final String DEFAULT_CATEGORY_CLASSPATH_LOCATION = "/";

    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", required = true, readonly = true)
    private MavenSession session;

    @Component
    @Requirement
    private BuildPluginManager pluginManager;

    @Parameter(defaultValue = "${project.build.directory}", required = true)
    private String buildDirectory;

    @Parameter(defaultValue = "${project.build.directory}/repository", required = true)
    private String destinationDirectory;

    @Component
    @Requirement
    private P2ApplicationLauncher launcher;


    /**
     * Specifies a file containing category definitions.
     */
    @Parameter(defaultValue = "")
    private String categoryFileURL;

    /**
     * Optional line of additional arguments passed to the p2 application launcher.
     */
    @Parameter(defaultValue = "false")
    private boolean pedantic;

    /**
     * Skip invalid arguments.
     *
     * <p>
     * This flag controls if the processing should be continued on invalid artifacts. It defaults to false to keep the
     * old behavior (break on invalid artifacts).
     */
    @Parameter(defaultValue = "false")
    private boolean skipInvalidArtifacts;

    /**
     * Specifies whether to compress generated update site.
     */
    @Parameter(defaultValue = "true")
    private boolean compressSite;

    /**
     * Kill the forked process after a certain number of seconds. If set to 0, wait forever for the
     * process, never timing out.
     */
    @Parameter(defaultValue = "0", alias = "p2.timeout")
    private int forkedProcessTimeoutInSeconds;

    /**
     * Specifies additional arguments to p2Launcher, for example -consoleLog -debug -verbose
     */
    @Parameter(defaultValue = "")
    private String additionalArgs;

    /**
     * Dependency injection container - used to get some components programatically
     */
    private PlexusContainer container;

    /**
     * Aether Repository System
     * Declared as raw Object type as different objects are injected in different Maven versions:
     * * 3.0.0 and above -> org.sonatype.aether...
     * * 3.1.0 and above -> org.eclipse.aether...
     */
    private Object repoSystem;

    /**
     * The current repository/network configuration of Maven.
     */
    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    private Object repoSession;

    /**
     * The project's remote repositories to use for the resolution of project dependencies.
     */
    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true)
    private List<Object> projectRepos;

    @Parameter(readonly = true)
    private List<P2Artifact> artifacts;

    /**
     * A list of artifacts that define eclipse features
     */
    @Parameter(readonly = true)
    private List<P2Artifact> features;

    /**
     * A list of Eclipse artifacts that should be downloaded from P2 repositories
     */
    @Parameter(readonly = true)
    private List<EclipseArtifact> p2;

    // artifacts checksum parameters
    /**
     * Whether to generate a checksum of all declared artifacts before executing this goal.
     * The checksum will be output to a "p2.hash" file.
     * It can be used to skip this goal if the generated checksum is the same as the one in the file.
     */
    @Parameter(property="p2.artifactsChecksum.generate", required = true, defaultValue = "false")
    private boolean artifactsChecksumGenerate;

    /**
     * Whether to skip this goal when the generated checksum is the same as the one in the "p2.hash" file.
     */
    @Parameter(property="p2.artifactsChecksum.skipIfEqual", required = true, defaultValue = "true")
    private boolean artifactsChecksumSkipIfEqual;

    /**
     * The dependencies checksum file.
     */
    @Parameter(property="p2.artifactsChecksum", required = true, defaultValue = "${project.basedir}/p2.hash")
    private File artifactsChecksum;

	private String artifactsChecksumHash = null;
	private static final String artifactsChecksumHashKey = "artifactsHash";

    /**
     * Logger retrieved from the Maven internals.
     * It's the recommended way to do it...
     */
    private Log log = getLog();

    /**
     * Folder which the jar files bundled by the ArtifactBundler will be copied to
     */
    private File bundlesDestinationFolder;

    /**
     * Folder which the feature jar files bundled by the ArtifactBundler will be copied to
     */
    private File featuresDestinationFolder;

    /**
     * Processing entry point.
     * Method that orchestrates the execution of the plugin.
     */
    @Override
    public void execute() {
        try {
            initializeEnvironment();
            initializeRepositorySystem();
            if (checkForHash()) {
            	getLog().info("Skipping execution because the p2 site was already generated for this set of declared artifacts");
            	return;
            }
            processArtifacts();
            processFeatures();
            processEclipseArtifacts();
            executeP2PublisherPlugin();
            executeCategoryPublisher();
            cleanupEnvironment();
            saveHash();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

	private String computeHashForArtifacts() {
    	List<Integer> artifactsHashes = new ArrayList<Integer>();
    	for (P2Artifact p2Artifact : artifacts) {
    		artifactsHashes.add(p2Artifact.getHash());
		}
    	Integer finalHash = Arrays.hashCode(artifactsHashes.toArray(new Integer[0]));

    	return finalHash.toString();
    }

    private boolean checkForHash() {
    	if (!artifactsChecksumGenerate) {
    		// build will not generate a hash so it is not possible to check
    		return false;
    	}
    	// generate the dependencies checksum
    	this.artifactsChecksumHash = computeHashForArtifacts();

    	// skip if equal and file exists : let's check
		if (artifactsChecksumSkipIfEqual && artifactsChecksum != null && artifactsChecksum.exists()) {
			Properties prop = new Properties();
			InputStream input = null;

			try {
				input = new FileInputStream(artifactsChecksum);
				prop.load(input);

				String hash = prop.getProperty(artifactsChecksumHashKey);
				if (hash != null) {
					if (StringUtils.equals(hash, this.artifactsChecksumHash)) {
						return true;
					}
				}
			} catch (IOException ex) {
				ex.printStackTrace();
			} finally {
				if (input != null) {
					try {
						input.close();
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			}
		}

		return false;
	}

    private void saveHash() {
    	if (artifactsChecksum == null || artifactsChecksumGenerate == false || artifactsChecksumHash == null) {
    		return;
    	}

    	Properties prop = new Properties();
		OutputStream output = null;
		try {
			output = new FileOutputStream(artifactsChecksum);

			prop.setProperty(artifactsChecksumHashKey, this.artifactsChecksumHash);

			prop.store(output, null);
		} catch (IOException ex) {
			ex.printStackTrace();
		} finally {
			if (output != null) {
				try {
					output.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
	}

	private void initializeEnvironment() throws IOException {
        Logger.initialize(log);
        bundlesDestinationFolder = new File(buildDirectory, BUNDLES_DESTINATION_FOLDER);
        featuresDestinationFolder = new File(buildDirectory, FEATURES_DESTINATION_FOLDER);
        FileUtils.deleteDirectory(new File(buildDirectory, BUNDLES_TOP_FOLDER));
        FileUtils.forceMkdir(bundlesDestinationFolder);
        FileUtils.forceMkdir(featuresDestinationFolder);
        artifacts = artifacts != null ? artifacts : new ArrayList<P2Artifact>();
        features = features != null ? features : new ArrayList<P2Artifact>();
        p2 = p2 != null ? p2 : new ArrayList<EclipseArtifact>();
    }

    private void initializeRepositorySystem() {
        if (repoSystem == null) {
            repoSystem = lookup("org.eclipse.aether.RepositorySystem");
        }
        if (repoSystem == null) {
            repoSystem = lookup("org.sonatype.aether.RepositorySystem");
        }
        Preconditions.checkNotNull(repoSystem, "Could not initialize RepositorySystem");
    }

    private Object lookup(String role) {
        try {
            return container.lookup(role);
        } catch (ComponentLookupException ex) {
        }
        return null;
    }

    private void processArtifacts() {
        Multimap<P2Artifact, ResolvedArtifact> resolvedArtifacts = resolveArtifacts();
        Set<Artifact> processedArtifacts = processRootArtifacts(resolvedArtifacts);
        processTransitiveArtifacts(resolvedArtifacts, processedArtifacts);
    }

    private Set<Artifact> processRootArtifacts(Multimap<P2Artifact, ResolvedArtifact> processedArtifacts) {
        Set<Artifact> bundledArtifacts = Sets.newHashSet();
        for (P2Artifact p2Artifact : artifacts) {
            for (ResolvedArtifact resolvedArtifact : processedArtifacts.get(p2Artifact)) {
                if (resolvedArtifact.isRoot()) {
                    if (bundledArtifacts.add(resolvedArtifact.getArtifact())) {
                        bundleArtifact(p2Artifact, resolvedArtifact);
                    } else {
                        String message = String.format("p2-maven-plugin misconfiguration" +
                                "\n\n\tJar [%s] is configured as an artifact multiple times. " +
                                "\n\tRemove the duplicate artifact definitions.\n", resolvedArtifact.getArtifact());
                        throw new RuntimeException(message);
                    }
                }
            }
        }
        return bundledArtifacts;
    }

    private void processTransitiveArtifacts(Multimap<P2Artifact, ResolvedArtifact> resolvedArtifacts, Set<Artifact> bundledArtifacts) {
        // then bundle transitive artifacts
        for (P2Artifact p2Artifact : artifacts) {
            for (ResolvedArtifact resolvedArtifact : resolvedArtifacts.get(p2Artifact)) {
                if (!resolvedArtifact.isRoot()) {
                    if (!bundledArtifacts.contains(resolvedArtifact.getArtifact())) {
                        try {
                            bundleArtifact(p2Artifact, resolvedArtifact);
                            bundledArtifacts.add(resolvedArtifact.getArtifact());
                        } catch (final RuntimeException ex) {
                            if (skipInvalidArtifacts) {
                                log.warn(String.format("Skip artifact=[%s]: %s", p2Artifact.getId(), ex.getMessage()));
                            } else {
                                throw ex;
                            }
                        }
                    } else {
                        log.debug(String.format("Not bundling transitive dependency since it has already been bundled [%s]", resolvedArtifact.getArtifact()));
                    }
                }
            }
        }
    }

    private void processFeatures() {
        // artifacts should already have been resolved by processArtifacts()
        Multimap<P2Artifact, ResolvedArtifact> resolvedFeatures = resolveFeatures();
        // then bundle the artifacts including the transitive dependencies (if specified so)
        log.info("Resolved " + resolvedFeatures.size() + " features");
        for (P2Artifact p2Artifact : features) {
            for (ResolvedArtifact resolvedArtifact : resolvedFeatures.get(p2Artifact)) {
                handleFeature(p2Artifact, resolvedArtifact);
            }
        }
    }

    private Multimap<P2Artifact, ResolvedArtifact> resolveArtifacts() {
        Multimap<P2Artifact, ResolvedArtifact> resolvedArtifacts = ArrayListMultimap.create();
        for (P2Artifact p2Artifact : artifacts) {
            logResolving(p2Artifact);
            ArtifactResolutionResult resolutionResult = resolveArtifact(p2Artifact);
            resolvedArtifacts.putAll(p2Artifact, resolutionResult.getResolvedArtifacts());
        }
        return resolvedArtifacts;
    }

    private Multimap<P2Artifact, ResolvedArtifact> resolveFeatures() {
        Multimap<P2Artifact, ResolvedArtifact> resolvedArtifacts = ArrayListMultimap.create();
        for (P2Artifact p2Artifact : features) {
            logResolving(p2Artifact);
            ArtifactResolutionResult resolutionResult = resolveArtifact(p2Artifact);
            resolvedArtifacts.putAll(p2Artifact, resolutionResult.getResolvedArtifacts());
        }
        return resolvedArtifacts;
    }

    private void logResolving(EclipseArtifact p2) {
        log.info(String.format("Resolving artifact=[%s] source=[%s]", p2.getId(),
                p2.shouldIncludeSources()));
    }

    private void logResolving(P2Artifact p2) {
        log.info(String.format("Resolving artifact=[%s] transitive=[%s] source=[%s]", p2.getId(), p2.shouldIncludeTransitive(),
                p2.shouldIncludeSources()));
    }

    private ArtifactResolutionResult resolveArtifact(P2Artifact p2Artifact) {
        ArtifactResolutionRequest resolutionRequest = ArtifactResolutionRequest.builder()
                .rootArtifactId(p2Artifact.getId())
                .resolveSource(p2Artifact.shouldIncludeSources())
                .resolveTransitive(p2Artifact.shouldIncludeTransitive())
                .excludes(p2Artifact.getExcludes())
                .build();
        ArtifactResolutionResult resolutionResult = getArtifactResolver().resolve(resolutionRequest);
        logResolved(resolutionRequest, resolutionResult);
        return resolutionResult;
    }

    private ArtifactResolver getArtifactResolver() {
        return new AetherResolver(repoSystem, repoSession, projectRepos);
    }

    private void logResolved(ArtifactResolutionRequest resolutionRequest, ArtifactResolutionResult resolutionResult) {
        for (ResolvedArtifact resolvedArtifact : resolutionResult.getResolvedArtifacts()) {
            log.info("\t [JAR] " + resolvedArtifact.getArtifact());
            if (resolvedArtifact.getSourceArtifact() != null) {
                log.info("\t [SRC] " + resolvedArtifact.getSourceArtifact().toString());
            } else if (resolutionRequest.isResolveSource()) {
                log.warn("\t [SRC] Failed to resolve source for artifact " + resolvedArtifact.getArtifact().toString());
            }
        }
    }

    private void bundleArtifact(P2Artifact p2Artifact, ResolvedArtifact resolvedArtifact) {
        P2Validator.validateBundleRequest(p2Artifact, resolvedArtifact);
        ArtifactBundler bundler = getArtifactBundler();
        ArtifactBundlerInstructions bundlerInstructions = P2Helper.createBundlerInstructions(p2Artifact, resolvedArtifact);
        ArtifactBundlerRequest bundlerRequest = P2Helper.createBundlerRequest(p2Artifact, resolvedArtifact, bundlesDestinationFolder);
        bundler.execute(bundlerRequest, bundlerInstructions);
    }

    private void handleFeature(P2Artifact p2Artifact, ResolvedArtifact resolvedArtifact) {
        log.debug("Handling feature " + p2Artifact.getId());
        ArtifactBundlerRequest bundlerRequest = P2Helper.createBundlerRequest(p2Artifact, resolvedArtifact, featuresDestinationFolder);
        try {
            File inputFile = bundlerRequest.getBinaryInputFile();
            File outputFile = bundlerRequest.getBinaryOutputFile();
            //This will also copy the input to the output
            JarUtils.adjustFeatureQualifierVersionWithTimestamp(inputFile, outputFile);
            log.info("Copied " + inputFile + " to " + outputFile);
        } catch (Exception ex) {
            throw new RuntimeException("Error while bundling jar or source: " + bundlerRequest.getBinaryInputFile().getName(), ex);
        }
    }

    private void processEclipseArtifacts() {
        DefaultEclipseResolver resolver = new DefaultEclipseResolver(projectRepos, bundlesDestinationFolder);
        for (EclipseArtifact artifact : p2) {
            logResolving(artifact);
            String[] tokens = artifact.getId().split(":");
            if (tokens.length != 2) {
                throw new RuntimeException("Wrong format " + artifact.getId());
            }
            EclipseResolutionRequest request = new EclipseResolutionRequest(tokens[0], tokens[1], artifact.shouldIncludeSources());
            resolver.resolve(request);
        }
    }

    private ArtifactBundler getArtifactBundler() {
        return new AquteBundler(pedantic);
    }

    private void executeP2PublisherPlugin() throws IOException, MojoExecutionException {
        prepareDestinationDirectory();
        BundlePublisher publisher = BundlePublisher.builder()
                .mavenProject(project)
                .mavenSession(session)
                .buildPluginManager(pluginManager)
                .compressSite(compressSite)
                .additionalArgs(additionalArgs)
                .build();
        publisher.execute();
    }

    private void prepareDestinationDirectory() throws IOException {
        FileUtils.deleteDirectory(new File(destinationDirectory));
    }

    private void executeCategoryPublisher() throws AbstractMojoExecutionException, IOException {
        prepareCategoryLocationFile();
        CategoryPublisher publisher = CategoryPublisher.builder()
                .p2ApplicationLauncher(launcher)
                .additionalArgs(additionalArgs)
                .forkedProcessTimeoutInSeconds(forkedProcessTimeoutInSeconds)
                .categoryFileLocation(categoryFileURL)
                .metadataRepositoryLocation(destinationDirectory)
                .build();
        publisher.execute();
    }

    private void prepareCategoryLocationFile() throws IOException {
        if (StringUtils.isBlank(categoryFileURL)) {
            InputStream is = getClass().getResourceAsStream(DEFAULT_CATEGORY_CLASSPATH_LOCATION + DEFAULT_CATEGORY_FILE);
            File destinationFolder = new File(destinationDirectory);
            destinationFolder.mkdirs();
            File categoryDefinitionFile = new File(destinationFolder, DEFAULT_CATEGORY_FILE);
            FileWriter writer = new FileWriter(categoryDefinitionFile);
            IOUtils.copy(is, writer, "UTF-8");
            IOUtils.closeQuietly(writer);
            categoryFileURL = categoryDefinitionFile.getAbsolutePath();
        }
    }

    private void cleanupEnvironment() throws IOException {
        File workFolder = new File(buildDirectory, BUNDLES_TOP_FOLDER);
        try {
            FileUtils.deleteDirectory(workFolder);
        } catch (IOException ex) {
            log.warn("Cannot cleanup the work folder " + workFolder.getAbsolutePath());
        }
    }

    @Override
    public void contextualize(Context context) throws ContextException {
        this.container = (PlexusContainer) context.get(PlexusConstants.PLEXUS_KEY);
    }

}
