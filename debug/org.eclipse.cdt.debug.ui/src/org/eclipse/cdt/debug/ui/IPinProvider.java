/*****************************************************************
 * Copyright (c) 2010, 2011 Texas Instruments and others
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Patrick Chuong (Texas Instruments) - Pin and Clone Supports (331781)
 *     Patrick Chuong (Texas Instruments) - Add support for icon overlay in the debug view (Bug 334566)
 *****************************************************************/
package org.eclipse.cdt.debug.ui;

import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IWorkbenchPart;

/**
 * Debug element that wants to enable pin capability should be adaptable to this interface.
 * <br><br>
 * When the user presses the 'Pin' action in a view that supports debug context pinning, the
 * DebugEventFilterService calls the <code>pin</code> method with the selected debug context.
 * If more than one debug context is selected, the <code>pin</code> method is called multiple times.
 * The <code>pin</code> method should return a handle for the pinned debug context and when
 * there is a debug context change event generated by the debug context manager,
 * <code>isPinnedTo</code> will be call by the DebugEventFilterService to determine whether the
 * debug context in question is pinned to the handle returned by the <code>pin</code> method.
 *
 *  @since 7.1
 */
public interface IPinProvider {
	/**
	 * Pin element color descriptor.
	 */
	public interface IPinElementColorDescriptor {
		/**
		 * Default number of color count.
		 */
		final int DEFAULT_COLOR_COUNT = 3;

		/**
		 * An undefined color
		 */
		int UNDEFINED = -1;

		/**
		 * Green color (Default)
		 */
		int GREEN = 0;

		/**
		 * Red color
		 */
		int RED = 1;

		/**
		 * Blue color
		 */
		int BLUE = 2;

		/**
		 * Returns the overlay pin color. The overlay pin will be used to decorate the debug view for an element that
		 * is pinned to a view.
		 *
		 * @return one of the overlay colors
		 */
		int getOverlayColor();

		/**
		 * Returns the toolbar pin action image description to use when the view is pinned, can be <code>null</code>.
		 * If <code>null</code>, then the default image description will be used.
		 *
		 * @return the icon descriptor
		 */
		ImageDescriptor getToolbarIconDescriptor();
	}

	/**
	 * Pin element handler interface.
	 */
	public interface IPinElementHandle {
		/**
		 * Returns the debug context for this handle.
		 *
		 * @return the debug context
		 */
		Object getDebugContext();

		/**
		 * Returns the label that will be used in the pinned view's description.
		 *
		 * @return the handle label
		 */
		String getLabel();

		/**
		 * Returns color descriptor for this element.
		 *
		 * @return the color descriptor, can be <code>null</code>
		 */
		IPinElementColorDescriptor getPinElementColorDescriptor();
	}

	/**
	 * A callback interface that can be used by an IPinProvider to indicate
	 * that the model has changed for a pinned view and that the view must be
	 * refreshed.
	 *
	 * @noimplement This interface is not intended to be implemented by clients.
	 */
	public interface IPinModelListener {
		/**
		 * Model changed handler that will cause the view to update.
		 *
		 * @param newSelection the new selection, if {@code null} the view will blank out.
		 *
		 */
		void modelChanged(ISelection newSelection);
	}

	/**
	 * Returns whether the debug context is pinnable.
	 *
	 * @param part the workbench part
	 * @param debugContext the debug context in question
	 * @return true if the debug context is pinnable
	 */
	boolean isPinnable(IWorkbenchPart part, Object debugContext);

	/**
	 * Pin the debug context and returns a handle for the pinned debug context.
	 *
	 * @param part the workbench part
	 * @param debugContext the debug context to pin to
	 * @return a handle for the pinned debug context
	 */
	IPinElementHandle pin(IWorkbenchPart part, Object debugContext, IPinModelListener listener);

	/**
	 * Unpin the debug context for the given pin handle.
	 *
	 * @param part the workbench part
	 * @param handle the handle for the pinned debug context
	 */
	void unpin(IWorkbenchPart part, IPinElementHandle handle);

	/**
	 * Returns true if the debug context belongs to the handle. If returning true,
	 * then the debug context change event will be delegated to the view.
	 *
	 * @param debugContext the debug context in question
	 * @param handle an existing pinned debug context handle
	 * @return true to delegate debug context change event to the view
	 */
	boolean isPinnedTo(Object debugContext, IPinElementHandle handle);
}
