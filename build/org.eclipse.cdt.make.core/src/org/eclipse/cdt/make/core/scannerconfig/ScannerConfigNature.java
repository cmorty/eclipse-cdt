/**********************************************************************
 * Copyright (c) 2004 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors: 
 * IBM - Initial API and implementation
 **********************************************************************/
package org.eclipse.cdt.make.core.scannerconfig;

import org.eclipse.cdt.core.CCorePlugin;
import org.eclipse.cdt.core.ICDescriptor;
import org.eclipse.cdt.make.core.MakeCorePlugin;
import org.eclipse.cdt.make.core.MakeScannerProvider;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IProjectNature;

/**
 * @see IProjectNature
 */
public class ScannerConfigNature implements IProjectNature {
	
	public final static String NATURE_ID = MakeCorePlugin.getUniqueIdentifier() + ".ScannerConfigNature"; //$NON-NLS-1$
	private IProject fProject;

	/**
	 * @see IProjectNature#configure
	 */
	public void configure() throws CoreException {
		IProjectDescription description = getProject().getDescription();
		ICommand[] commands = description.getBuildSpec();
		for (int i = 0; i < commands.length; ++i) {
			if (commands[i].getBuilderName().equals(ScannerConfigBuilder.BUILDER_ID)) {
				return;
			}
		}
		ICommand command = description.newCommand();
		command.setBuilderName(ScannerConfigBuilder.BUILDER_ID);
		ICommand[] newCommands = new ICommand[commands.length + 1];
		System.arraycopy(commands, 0, newCommands, 0, commands.length);
		newCommands[commands.length] = command;
		description.setBuildSpec(newCommands);
		getProject().setDescription(description, null);
		
		// set default project scanner config settings
	}

	/**
	 * @see IProjectNature#deconfigure
	 */
	public void deconfigure() throws CoreException {
		IProjectDescription description = getProject().getDescription();
		ICommand[] commands = description.getBuildSpec();
		for (int i = 0; i < commands.length; ++i) {
			if (commands[i].getBuilderName().equals(ScannerConfigBuilder.BUILDER_ID)) {
				ICommand[] newCommands = new ICommand[commands.length - 1];
				System.arraycopy(commands, 0, newCommands, 0, i);
				System.arraycopy(commands, i + 1, newCommands, i, commands.length - i - 1);
				description.setBuildSpec(newCommands);
				break;
			}
		}
		getProject().setDescription(description, null);
	}

	/**
	 * @see IProjectNature#getProject
	 */
	public IProject getProject()  {
		return fProject;
	}

	/**
	 * @see IProjectNature#setProject
	 */
	public void setProject(IProject project)  {
		fProject = project;
	}
	
	public static void addScannerConfigNature(IProject project) throws CoreException {
		IProjectDescription description = project.getDescription();
		if (description.hasNature(NATURE_ID))
			return;
		String[] ids = description.getNatureIds();
		String[] newIds = new String[ids.length + 1];
		System.arraycopy(ids, 0, newIds, 0, ids.length);
		newIds[ids.length] = NATURE_ID;
		description.setNatureIds(newIds);
		project.setDescription(description, null);
		
		// set DiscoveredScannerInfoProvider as a default one for the project
		updateProjectsScannerInfoProvider(project, true);
	}
	
	public static void removeScannerConfigNature(IProject project) throws CoreException {
		IProjectDescription description = project.getDescription();
		if (!description.hasNature(NATURE_ID))
			return;
		String[] ids = description.getNatureIds();
		for (int i = 0; i < ids.length; ++i) {
			if (ids[i].equals(NATURE_ID)) {
				String[] newIds = new String[ids.length - 1];
				System.arraycopy(ids, 0, newIds, 0, i);
				System.arraycopy(ids, i + 1, newIds, i, ids.length - i - 1);
				description.setNatureIds(newIds);
				project.setDescription(description, null);
			}
		}

		// fall back to MakeScannerProvider
		updateProjectsScannerInfoProvider(project, false);
	}

	/**
	 * Returns build command as stored in .project file
	 * 
	 * @param project
	 * @param builderID
	 * @return ICommand
	 * @throws CoreException
	 */
	public static ICommand getBuildSpec(IProject project, String builderID) throws CoreException {
		IProjectDescription description = project.getDescription();
		ICommand[] commands = description.getBuildSpec();
		for (int i = 0; i < commands.length; ++i) {
			if (commands[i].getBuilderName().equals(builderID)) {
				return commands[i];
			}
		}
		return null;
	}

	/**
	 * @param project
	 * @param b
	 */
	private static void updateProjectsScannerInfoProvider(IProject project, boolean discovered) {
		try {
			ICDescriptor desc = CCorePlugin.getDefault().getCProjectDescription(project);
			desc.remove(CCorePlugin.BUILD_SCANNER_INFO_UNIQ_ID);
			desc.create(CCorePlugin.BUILD_SCANNER_INFO_UNIQ_ID, (discovered)?
					DiscoveredScannerInfoProvider.INTERFACE_IDENTITY:
					MakeScannerProvider.INTERFACE_IDENTITY);
		} catch (CoreException e) {
			MakeCorePlugin.log(e.getStatus());
		}
	}
}
