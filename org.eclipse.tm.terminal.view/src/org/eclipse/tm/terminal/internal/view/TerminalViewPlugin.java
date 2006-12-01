/*******************************************************************************
 * Copyright (c) 2006 Wind River Systems, Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Wind River Systems, Inc. - initial implementation
 *     
 *******************************************************************************/

package org.eclipse.tm.terminal.internal.view;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Iterator;

import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.resource.ImageRegistry;
import org.eclipse.tm.terminal.Logger;
import org.eclipse.ui.plugin.AbstractUIPlugin;

public class TerminalViewPlugin extends AbstractUIPlugin {
	protected static TerminalViewPlugin fDefault;
  public static final String  PLUGIN_HOME                                     = "org.eclipse.tm.terminal"; //$NON-NLS-1$
  public static final String  HELP_VIEW                                       = PLUGIN_HOME + ".terminal_view"; //$NON-NLS-1$

	/**
	 * The constructor.
	 */
	public TerminalViewPlugin() {
		fDefault = this;
	}
	protected void initializeImageRegistry(ImageRegistry imageRegistry) {
		HashMap map;

		map = new HashMap();

		try {
			// Local toolbars
			map.put(ImageConsts.IMAGE_NEW_TERMINAL, "newterminal.gif"); //$NON-NLS-1$
			map.put(ImageConsts.IMAGE_CLCL_CONNECT, "connect_co.gif"); //$NON-NLS-1$
			map.put(ImageConsts.IMAGE_CLCL_DISCONNECT, "disconnect_co.gif"); //$NON-NLS-1$
			map.put(ImageConsts.IMAGE_CLCL_SETTINGS, "properties_tsk.gif"); //$NON-NLS-1$

			loadImageRegistry(imageRegistry, ImageConsts.IMAGE_DIR_LOCALTOOL, map);

			map.clear();

			// Enabled local toolbars
			map.put(ImageConsts.IMAGE_NEW_TERMINAL, "newterminal.gif"); //$NON-NLS-1$
			map.put(ImageConsts.IMAGE_ELCL_CONNECT, "connect_co.gif"); //$NON-NLS-1$
			map.put(ImageConsts.IMAGE_ELCL_DISCONNECT, "disconnect_co.gif"); //$NON-NLS-1$
			map.put(ImageConsts.IMAGE_ELCL_SETTINGS, "properties_tsk.gif"); //$NON-NLS-1$

			loadImageRegistry(imageRegistry, ImageConsts.IMAGE_DIR_ELCL, map);

			map.clear();

			// Disabled local toolbars
			map.put(ImageConsts.IMAGE_NEW_TERMINAL, "newterminal.gif"); //$NON-NLS-1$
			map.put(ImageConsts.IMAGE_DLCL_CONNECT, "connect_co.gif"); //$NON-NLS-1$
			map.put(ImageConsts.IMAGE_DLCL_DISCONNECT, "disconnect_co.gif"); //$NON-NLS-1$
			map.put(ImageConsts.IMAGE_DLCL_SETTINGS, "properties_tsk.gif"); //$NON-NLS-1$

			loadImageRegistry(imageRegistry, ImageConsts.IMAGE_DIR_DLCL, map);

			map.clear();
		} catch (MalformedURLException malformedURLException) {
			malformedURLException.printStackTrace();
		}
	}
	protected void initializeDefaultPreferences(IPreferenceStore store) {
		store.setDefault(TerminalPreferencePage.PREF_LIMITOUTPUT,
				TerminalPreferencePage.DEFAULT_LIMITOUTPUT);
		store.setDefault(TerminalPreferencePage.PREF_BUFFERLINES,
				TerminalPreferencePage.DEFAULT_BUFFERLINES);
		store.setDefault(TerminalPreferencePage.PREF_TIMEOUT_SERIAL,
				TerminalPreferencePage.DEFAULT_TIMEOUT_SERIAL);
		store.setDefault(TerminalPreferencePage.PREF_TIMEOUT_NETWORK,
				TerminalPreferencePage.DEFAULT_TIMEOUT_NETWORK);
	}
	/**
	 * Returns the shared instance.
	 */
	public static TerminalViewPlugin getDefault() {
		return fDefault;
	}

	public static boolean isLogInfoEnabled() {
		return isOptionEnabled(Logger.TRACE_DEBUG_LOG_INFO);
	}
	public static boolean isLogErrorEnabled() {
		return isOptionEnabled(Logger.TRACE_DEBUG_LOG_ERROR);
	}
	public static boolean isLogEnabled() {
		return isOptionEnabled(Logger.TRACE_DEBUG_LOG);
	}

	public static boolean isOptionEnabled(String strOption) {
		String strEnabled;
		Boolean boolEnabled;
		boolean bEnabled;

		strEnabled = Platform.getDebugOption(strOption);
		if (strEnabled == null)
			return false;

		boolEnabled = new Boolean(strEnabled);
		bEnabled = boolEnabled.booleanValue();

		return bEnabled;
	}
	protected void loadImageRegistry(ImageRegistry imageRegistry,
			String strDir, HashMap map) throws MalformedURLException {
		URL url;
		ImageDescriptor imageDescriptor;
		Iterator keys;
		String strKey;
		String strFile;

		keys = map.keySet().iterator();

		while (keys.hasNext()) {
			strKey = (String) keys.next();
			strFile = (String) map.get(strKey);

			if (strFile != null) {
				url = TerminalViewPlugin.getDefault().getBundle().getEntry(
						ImageConsts.IMAGE_DIR_ROOT + strDir + strFile);
				imageDescriptor = ImageDescriptor.createFromURL(url);
				imageRegistry.put(strKey, imageDescriptor);
			}
		}
	}
}
