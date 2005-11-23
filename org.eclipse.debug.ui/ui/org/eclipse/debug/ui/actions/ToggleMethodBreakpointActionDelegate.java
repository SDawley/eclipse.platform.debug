/*******************************************************************************
 * Copyright (c) 2000, 2005 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.debug.ui.actions;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.internal.ui.actions.breakpoints.ToggleBreakpointObjectActionDelegate;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IWorkbenchPart;

/**
 * A toggle method breakpoint action that can be contributed an object
 * contribution. The action will toggle method breakpoints on objects
 * that provide an <code>IToggleBreakpointsTarget</code> adapter.
 * <p>
 * Clients may reference/contribute this class as an object contribution
 * action delegate in plug-in XML. This class is not intended to be
 * subclassed.
 * </p>
 * @since 3.0
 */
public class ToggleMethodBreakpointActionDelegate extends ToggleBreakpointObjectActionDelegate {
	/* (non-Javadoc)
	 * @see org.eclipse.debug.internal.ui.actions.ToggleBreakpointObjectActionDelegate#performAction(org.eclipse.debug.internal.ui.actions.IToggleBreakpointsTarget, org.eclipse.ui.IWorkbenchPart, org.eclipse.jface.viewers.ISelection)
	 */
	protected void performAction(IToggleBreakpointsTarget target, IWorkbenchPart part, ISelection selection) throws CoreException {
		target.toggleMethodBreakpoints(part, selection);
	}
}
