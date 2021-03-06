/*******************************************************************************
 * Copyright (c) 2015, 2016 QNX Software Systems and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.cdt.cmake.core.internal;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import org.eclipse.cdt.cmake.core.ICMakeToolChainFile;
import org.eclipse.cdt.cmake.core.ICMakeToolChainManager;
import org.eclipse.cdt.cmake.is.core.CompileCommandsJsonParser;
import org.eclipse.cdt.cmake.is.core.IIndexerInfoConsumer;
import org.eclipse.cdt.cmake.is.core.ParseRequest;
import org.eclipse.cdt.core.CommandLauncherManager;
import org.eclipse.cdt.core.ConsoleOutputStream;
import org.eclipse.cdt.core.ErrorParserManager;
import org.eclipse.cdt.core.IConsoleParser;
import org.eclipse.cdt.core.build.CBuildConfiguration;
import org.eclipse.cdt.core.build.IToolChain;
import org.eclipse.cdt.core.envvar.EnvironmentVariable;
import org.eclipse.cdt.core.envvar.IEnvironmentVariable;
import org.eclipse.cdt.core.model.ICModelMarker;
import org.eclipse.cdt.core.parser.ExtendedScannerInfo;
import org.eclipse.cdt.core.parser.IScannerInfo;
import org.eclipse.cdt.core.resources.IConsole;
import org.eclipse.core.resources.IBuildConfiguration;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.jobs.Job;

public class CMakeBuildConfiguration extends CBuildConfiguration {

	public static final String CMAKE_GENERATOR = "cmake.generator"; //$NON-NLS-1$
	public static final String CMAKE_ARGUMENTS = "cmake.arguments"; //$NON-NLS-1$
	public static final String CMAKE_ENV = "cmake.environment"; //$NON-NLS-1$
	public static final String BUILD_COMMAND = "cmake.command.build"; //$NON-NLS-1$
	public static final String CLEAN_COMMAND = "cmake.command.clean"; //$NON-NLS-1$

	private ICMakeToolChainFile toolChainFile;
	private Map<IResource, IScannerInfo> infoPerResource;

	public CMakeBuildConfiguration(IBuildConfiguration config, String name) throws CoreException {
		super(config, name);

		ICMakeToolChainManager manager = Activator.getService(ICMakeToolChainManager.class);
		toolChainFile = manager.getToolChainFileFor(getToolChain());
	}

	public CMakeBuildConfiguration(IBuildConfiguration config, String name, IToolChain toolChain) {
		this(config, name, toolChain, null, "run"); //$NON-NLS-1$
	}

	public CMakeBuildConfiguration(IBuildConfiguration config, String name, IToolChain toolChain,
			ICMakeToolChainFile toolChainFile, String launchMode) {
		super(config, name, toolChain, launchMode);
		this.toolChainFile = toolChainFile;
	}

	public ICMakeToolChainFile getToolChainFile() {
		return toolChainFile;
	}

	private boolean isLocal() throws CoreException {
		IToolChain toolchain = getToolChain();
		return (Platform.getOS().equals(toolchain.getProperty(IToolChain.ATTR_OS))
				|| "linux-container".equals(toolchain.getProperty(IToolChain.ATTR_OS))) //$NON-NLS-1$
				&& (Platform.getOSArch().equals(toolchain.getProperty(IToolChain.ATTR_ARCH)));
	}

	@Override
	public IProject[] build(int kind, Map<String, String> args, IConsole console, IProgressMonitor monitor)
			throws CoreException {
		IProject project = getProject();

		try {
			String generator = getProperty(CMAKE_GENERATOR);
			if (generator == null) {
				generator = "Ninja"; //$NON-NLS-1$
			}

			project.deleteMarkers(ICModelMarker.C_MODEL_PROBLEM_MARKER, false, IResource.DEPTH_INFINITE);
			infoPerResource = new HashMap<>();

			ConsoleOutputStream outStream = console.getOutputStream();

			Path buildDir = getBuildDirectory();

			outStream.write(String.format(Messages.CMakeBuildConfiguration_BuildingIn, buildDir.toString()));

			// Make sure we have a toolchain file if cross
			if (toolChainFile == null && !isLocal()) {
				ICMakeToolChainManager manager = Activator.getService(ICMakeToolChainManager.class);
				toolChainFile = manager.getToolChainFileFor(getToolChain());

				if (toolChainFile == null) {
					// error
					console.getErrorStream().write(Messages.CMakeBuildConfiguration_NoToolchainFile);
					return null;
				}
			}

			boolean runCMake;
			switch (generator) {
			case "Ninja": //$NON-NLS-1$
				runCMake = !Files.exists(buildDir.resolve("build.ninja")); //$NON-NLS-1$
				break;
			case "Unix Makefiles": //$NON-NLS-1$
				runCMake = !Files.exists(buildDir.resolve("Makefile")); //$NON-NLS-1$
				break;
			default:
				runCMake = !Files.exists(buildDir.resolve("CMakeFiles")); //$NON-NLS-1$
			}

			if (runCMake) { // $NON-NLS-1$

				console.getOutputStream().write(String.format(Messages.CMakeBuildConfiguration_Configuring, buildDir));
				// clean output to make sure there is no content
				// incompatible with current settings (cmake config would fail)
				cleanBuildDirectory(buildDir);

				List<String> command = new ArrayList<>();

				command.add("cmake"); //$NON-NLS-1$
				command.add("-G"); //$NON-NLS-1$
				command.add(generator);

				if (toolChainFile != null) {
					command.add("-DCMAKE_TOOLCHAIN_FILE=" + toolChainFile.getPath().toString()); //$NON-NLS-1$
				}

				switch (getLaunchMode()) {
				// TODO what to do with other modes
				case "debug": //$NON-NLS-1$
					command.add("-DCMAKE_BUILD_TYPE=Debug"); //$NON-NLS-1$
					break;
				case "run": //$NON-NLS-1$
					command.add("-DCMAKE_BUILD_TYPE=Release"); //$NON-NLS-1$
					break;
				}
				command.add("-DCMAKE_EXPORT_COMPILE_COMMANDS=ON"); //$NON-NLS-1$

				String userArgs = getProperty(CMAKE_ARGUMENTS);
				if (userArgs != null) {
					command.addAll(Arrays.asList(userArgs.trim().split("\\s+"))); //$NON-NLS-1$
				}

				command.add(new File(project.getLocationURI()).getAbsolutePath());

				outStream.write(String.join(" ", command) + '\n'); //$NON-NLS-1$

				org.eclipse.core.runtime.Path workingDir = new org.eclipse.core.runtime.Path(
						getBuildDirectory().toString());
				Process p = startBuildProcess(command, new IEnvironmentVariable[0], workingDir, console, monitor);
				if (p == null) {
					console.getErrorStream().write(String.format(Messages.CMakeBuildConfiguration_Failure, "")); //$NON-NLS-1$
					return null;
				}

				watchProcess(p, console);
			}

			try (ErrorParserManager epm = new ErrorParserManager(project, getBuildDirectoryURI(), this,
					getToolChain().getErrorParserIds())) {
				epm.setOutputStream(console.getOutputStream());

				List<String> command = new ArrayList<>();

				String envStr = getProperty(CMAKE_ENV);
				List<IEnvironmentVariable> envVars = new ArrayList<>();
				if (envStr != null) {
					List<String> envList = CMakeUtils.stripEnvVars(envStr);
					for (String s : envList) {
						int index = s.indexOf("="); //$NON-NLS-1$
						if (index == -1) {
							envVars.add(new EnvironmentVariable(s));
						} else {
							envVars.add(new EnvironmentVariable(s.substring(0, index), s.substring(index + 1)));
						}
					}
				}

				String buildCommand = getProperty(BUILD_COMMAND);
				if (buildCommand == null) {
					command.add("cmake"); //$NON-NLS-1$
					command.add("--build"); //$NON-NLS-1$
					command.add("."); //$NON-NLS-1$
					if ("Ninja".equals(generator)) { //$NON-NLS-1$
						command.add("--"); //$NON-NLS-1$
						command.add("-v"); //$NON-NLS-1$
					}
				} else {
					command.addAll(Arrays.asList(buildCommand.split(" "))); //$NON-NLS-1$
				}

				outStream.write(String.join(" ", command) + '\n'); //$NON-NLS-1$

				org.eclipse.core.runtime.Path workingDir = new org.eclipse.core.runtime.Path(
						getBuildDirectory().toString());
				Process p = startBuildProcess(command, envVars.toArray(new IEnvironmentVariable[0]), workingDir,
						console, monitor);
				if (p == null) {
					console.getErrorStream().write(String.format(Messages.CMakeBuildConfiguration_Failure, "")); //$NON-NLS-1$
					return null;
				}

				watchProcess(p, new IConsoleParser[] { epm });

				project.refreshLocal(IResource.DEPTH_INFINITE, monitor);

				// parse compile_commands.json file
				// built-ins detection output goes to the build console, if the user requested
				// output
				processCompileCommandsFile(console, monitor);

				outStream.write(String.format(Messages.CMakeBuildConfiguration_BuildingComplete, epm.getErrorCount(),
						epm.getWarningCount(), buildDir.toString()));
			}

			return new IProject[] { project };
		} catch (IOException e) {
			throw new CoreException(Activator
					.errorStatus(String.format(Messages.CMakeBuildConfiguration_Building, project.getName()), e));
		}
	}

	@Override
	public void clean(IConsole console, IProgressMonitor monitor) throws CoreException {
		IProject project = getProject();
		try {
			String generator = getProperty(CMAKE_GENERATOR);

			project.deleteMarkers(ICModelMarker.C_MODEL_PROBLEM_MARKER, false, IResource.DEPTH_INFINITE);

			ConsoleOutputStream outStream = console.getOutputStream();

			Path buildDir = getBuildDirectory();

			if (!Files.exists(buildDir.resolve("CMakeFiles"))) { //$NON-NLS-1$
				outStream.write(Messages.CMakeBuildConfiguration_NotFound);
				return;
			}

			List<String> command = new ArrayList<>();
			String cleanCommand = getProperty(CLEAN_COMMAND);
			if (cleanCommand == null) {
				if (generator == null || generator.equals("Ninja")) { //$NON-NLS-1$
					command.add("ninja"); //$NON-NLS-1$
					command.add("clean"); //$NON-NLS-1$
				} else {
					command.add("make"); //$NON-NLS-1$
					command.add("clean"); //$NON-NLS-1$
				}
			} else {
				command.addAll(Arrays.asList(cleanCommand.split(" "))); //$NON-NLS-1$
			}

			IEnvironmentVariable[] env = new IEnvironmentVariable[0];

			outStream.write(String.join(" ", command) + '\n'); //$NON-NLS-1$

			org.eclipse.core.runtime.Path workingDir = new org.eclipse.core.runtime.Path(
					getBuildDirectory().toString());
			Process p = startBuildProcess(command, env, workingDir, console, monitor);
			if (p == null) {
				console.getErrorStream().write(String.format(Messages.CMakeBuildConfiguration_Failure, "")); //$NON-NLS-1$
				return;
			}

			watchProcess(p, console);

			outStream.write(Messages.CMakeBuildConfiguration_BuildComplete);

			project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
		} catch (IOException e) {
			throw new CoreException(Activator
					.errorStatus(String.format(Messages.CMakeBuildConfiguration_Cleaning, project.getName()), e));
		}
	}

	/**
	 * @param console the console to print the compiler output during built-ins
	 *                detection to or <code>null</code> if no separate console is to
	 *                be allocated. Ignored if workspace preferences indicate that
	 *                no console output is wanted.
	 * @param monitor the job's progress monitor
	 */
	private void processCompileCommandsFile(IConsole console, IProgressMonitor monitor) throws CoreException {
		CompileCommandsJsonParser parser = new CompileCommandsJsonParser(
				new ParseRequest(this, new CMakeIndexerInfoConsumer(this::setScannerInformation),
						CommandLauncherManager.getInstance().getCommandLauncher(this), console));
		parser.parse(monitor);
	}

	/**
	 * Recursively removes any files and directories found below the specified Path.
	 */
	private static void cleanDirectory(Path dir) throws IOException {
		SimpleFileVisitor<Path> deltor = new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Files.delete(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
				super.postVisitDirectory(dir, exc);
				Files.delete(dir);
				return FileVisitResult.CONTINUE;
			}
		};
		Path[] files = Files.list(dir).toArray(Path[]::new);
		for (Path file : files) {
			Files.walkFileTree(file, deltor);
		}
	}

	private void cleanBuildDirectory(Path buildDir) throws IOException {
		if (!Files.exists(buildDir))
			return;
		if (Files.isDirectory(buildDir))
			cleanDirectory(buildDir);
		// TODO: not a directory should we do something?
	}

	/**
	 * Overridden since the ScannerInfoCache mechanism does not satisfy our needs.
	 */
	// interface IScannerInfoProvider
	@Override
	public IScannerInfo getScannerInformation(IResource resource) {
		if (infoPerResource == null) {
			// no build was run yet, nothing detected
			infoPerResource = new HashMap<>();
			try {
				processCompileCommandsFile(null, new NullProgressMonitor());
			} catch (CoreException e) {
				Activator.log(e);
			}
		}
		return infoPerResource.get(resource);
	}

	private void setScannerInformation(Map<IResource, IScannerInfo> infoPerResource) {
		this.infoPerResource = infoPerResource;
	}

	private static class CMakeIndexerInfoConsumer implements IIndexerInfoConsumer {
		/**
		 * gathered IScannerInfo objects or <code>null</code> if no new IScannerInfo was
		 * received
		 */
		private Map<IResource, IScannerInfo> infoPerResource = new HashMap<>();
		private boolean haveUpdates;
		private final Consumer<Map<IResource, IScannerInfo>> resultSetter;

		/**
		 * @param resultSetter receives the all scanner information when processing is
		 *                     finished
		 */
		public CMakeIndexerInfoConsumer(Consumer<Map<IResource, IScannerInfo>> resultSetter) {
			this.resultSetter = Objects.requireNonNull(resultSetter);
		}

		@Override
		public void acceptSourceFileInfo(String sourceFileName, List<String> systemIncludePaths,
				Map<String, String> definedSymbols, List<String> includePaths, List<String> macroFiles,
				List<String> includeFiles) {
			IFile file = getFileForCMakePath(sourceFileName);
			if (file != null) {
				ExtendedScannerInfo info = new ExtendedScannerInfo(definedSymbols,
						systemIncludePaths.stream().toArray(String[]::new), macroFiles.stream().toArray(String[]::new),
						includeFiles.stream().toArray(String[]::new), includePaths.stream().toArray(String[]::new));
				infoPerResource.put(file, info);
				haveUpdates = true;
			}
		}

		/**
		 * Gets an IFile object that corresponds to the source file name given in CMake
		 * notation.
		 *
		 * @param sourceFileName the name of the source file, in CMake notation. Note
		 *                       that on windows, CMake writes filenames with forward
		 *                       slashes (/) such as {@code H://path//to//source.c}.
		 * @return a IFile object or <code>null</code>
		 */
		private IFile getFileForCMakePath(String sourceFileName) {
			org.eclipse.core.runtime.Path path = new org.eclipse.core.runtime.Path(sourceFileName);
			IFile file = ResourcesPlugin.getWorkspace().getRoot().getFileForLocation(path);
			// TODO maybe we need to introduce a strategy here to get the workbench resource
			// Possible build scenarios:
			// 1) linux native: should be OK as is
			// 2) linux host, building in container: should be OK as is
			// 3) windows native: Path.fromOSString()?
			// 4) windows host, building in linux container: ??? needs testing on windows
			return file;
		}

		@Override
		public void shutdown() {
			if (haveUpdates) {
				// we received updates
				resultSetter.accept(infoPerResource);
				infoPerResource = null;
				haveUpdates = false;
			}
		}
	}

	/** Overwritten since we do not parse console output to get scanner information.
	 */
	// interface IConsoleParser2
	@Override
	public boolean processLine(String line) {
		return true;
	}

	/** Overwritten since we do not parse console output to get scanner information.
	 */
	// interface IConsoleParser2
	@Override
	public boolean processLine(String line, List<Job> jobsArray) {
		return true;
	}

	/** Overwritten since we do not parse console output to get scanner information.
	 */
	// interface IConsoleParser2
	@Override
	public void shutdown() {
	}
}
