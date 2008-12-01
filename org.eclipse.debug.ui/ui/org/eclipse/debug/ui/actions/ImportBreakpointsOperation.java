/*******************************************************************************
 * Copyright (c) 2005, 2008 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/

package org.eclipse.debug.ui.actions;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.core.model.IBreakpointImportParticipant;
import org.eclipse.debug.internal.core.BreakpointManager;
import org.eclipse.debug.internal.ui.IInternalDebugUIConstants;
import org.eclipse.debug.internal.ui.importexport.breakpoints.IImportExportConstants;
import org.eclipse.debug.internal.ui.importexport.breakpoints.ImportExportMessages;
import org.eclipse.debug.ui.IDebugUIConstants;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.IWorkingSet;
import org.eclipse.ui.IWorkingSetManager;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.XMLMemento;

import com.ibm.icu.text.MessageFormat;

/**
 * Imports breakpoints from a file or string buffer into the workspace.
 * <p>
 * This class may be instantiated.
 * <p>
 * @since 3.2
 * @noextend This class is not intended to be subclassed by clients.
 */
public class ImportBreakpointsOperation implements IRunnableWithProgress {

	private boolean fOverwriteAll = false;

	private String fFileName = null;

	private boolean fCreateWorkingSets = false;

	private ArrayList fAdded = new ArrayList();
	
	private String fCurrentWorkingSetProperty = null;

	private BreakpointManager fManager = (BreakpointManager) DebugPlugin.getDefault().getBreakpointManager();
	
	/** 
	 * When a buffer is specified, a file is not used.
	 */
	private StringBuffer fBuffer = null;

	/**
	 * Constructs an operation to import breakpoints.
	 * 
	 * @param fileName the file to read breakpoints from - the file should have been 
	 *            created from an export operation
	 * @param overwrite whether imported breakpoints will overwrite existing equivalent breakpoints
	 * @param createWorkingSets whether breakpoint working sets should be created. Breakpoints
	 * 	are exported with information about the breakpoint working sets they belong to. Those
	 * 	working sets can be optionally re-created on import if they do not already exist in the
	 *            workspace.
	 */
	public ImportBreakpointsOperation(String fileName, boolean overwrite, boolean createWorkingSets) {
		fFileName = fileName;
		fOverwriteAll = overwrite;
		fCreateWorkingSets = createWorkingSets;
	}
	
	/**
	 * Constructs an operation to import breakpoints from a string buffer. The buffer
	 * must contain a memento created an {@link ExportBreakpointsOperation}.
	 * 
	 * @param buffer the string buffer to read breakpoints from - the file should have been 
	 *            created from an export operation
	 * @param overwrite whether imported breakpoints will overwrite existing equivalent breakpoints
	 * @param createWorkingSets whether breakpoint working sets should be created. Breakpoints
	 * 	are exported with information about the breakpoint working sets they belong to. Those
	 * 	working sets can be optionally re-created on import if they do not already exist in the
	 *            workspace.
	 * @since 3.5
	 */
	public ImportBreakpointsOperation(StringBuffer buffer, boolean overwrite, boolean createWorkingSets) {
		fBuffer = buffer;
		fOverwriteAll = overwrite;
		fCreateWorkingSets = createWorkingSets;
	}	

	/* (non-Javadoc)
	 * @see org.eclipse.core.resources.IWorkspaceRunnable#run(org.eclipse.core.runtime.IProgressMonitor)
	 */
	public void run(final IProgressMonitor monitor) throws InvocationTargetException {
		IWorkspaceRunnable wr = new IWorkspaceRunnable() {
			public void run(IProgressMonitor wmonitor) throws CoreException {
				try {
					Reader reader = null;
					if (fBuffer == null) {
						reader = new InputStreamReader(new FileInputStream(fFileName), "UTF-8"); //$NON-NLS-1$
					} else {
						reader = new StringReader(fBuffer.toString());
					}
					XMLMemento memento = XMLMemento.createReadRoot(reader);
					IMemento[] nodes = memento.getChildren(IImportExportConstants.IE_NODE_BREAKPOINT);
					IWorkspaceRoot workspace = ResourcesPlugin.getWorkspace().getRoot();
					monitor.beginTask(ImportExportMessages.ImportOperation_0, nodes.length);
					Map attributes = null;
					IBreakpointImportParticipant[] participants = null;
					for(int i = 0; i < nodes.length; i++) {
						if(monitor.isCanceled()) {
							return;
						}
						attributes = collectBreakpointProperties(nodes[i]);
						IResource resource = workspace.findMember((String) attributes.get(IImportExportConstants.IE_NODE_PATH));
						// filter resource breakpoints that do not exist in this workspace
						if(resource != null) {	
							try {
								participants = fManager.getImportParticipants((String) attributes.get(IImportExportConstants.IE_NODE_TYPE)); 
							}
							catch(CoreException ce) {}
							IMarker marker = findExistingMarker(attributes, participants);
							if(marker == null) {
								marker = resource.createMarker((String) attributes.get(IImportExportConstants.IE_NODE_TYPE));
								restoreBreakpoint(marker, attributes, participants);
							}
							else {
								if(fOverwriteAll) {
									marker.setAttributes(null);
									restoreBreakpoint(marker, attributes, participants);
								}
							}
						}
						fCurrentWorkingSetProperty = null;
						monitor.worked(1);
					}
					fManager.addBreakpoints((IBreakpoint[])fAdded.toArray(new IBreakpoint[fAdded.size()]));
				} 
				catch(FileNotFoundException e) {
					throw new CoreException(new Status(IStatus.ERROR, IDebugUIConstants.PLUGIN_ID, IDebugUIConstants.INTERNAL_ERROR, 
							MessageFormat.format("Breakpoint import file not found: {0}", new String[]{fFileName}), e)); //$NON-NLS-1$
				}
				catch (UnsupportedEncodingException e) {
					throw new CoreException(new Status(IStatus.ERROR, IDebugUIConstants.PLUGIN_ID, IDebugUIConstants.INTERNAL_ERROR, 
							MessageFormat.format("The import file was written in non-UTF-8 encoding.", new String[]{fFileName}), e)); //$NON-NLS-1$
				}
			}
		};
		try {
			ResourcesPlugin.getWorkspace().run(wr, monitor);
		} catch(CoreException e) {
			throw new InvocationTargetException(e);
		}
	}
	
	/**
	 * Returns a marker backing an existing breakpoint based on the given set of breakpoint attributes
	 * @param attributes
	 * @param participants
	 * @return the marker for an existing breakpoint or <code>null</code> if one could not be located
	 * @since 3.5
	 */
	protected IMarker findExistingMarker(Map attributes, IBreakpointImportParticipant[] participants) {
		IBreakpoint[] bps = fManager.getBreakpoints();		 
		for(int i = 0; i < bps.length; i++) {
			for(int j = 0; j < participants.length; j++) {
				try {
					if(participants[j].matches(attributes, bps[i])) {
						return bps[i].getMarker();
					}
				}
				catch(CoreException ce) {}
			}
		}
		return null;
	}
	
	/**
	 * Collects all of the properties for a breakpoint from the memento describing it.
	 * The values in the map will be one of:
	 * <ul>
	 * <li>{@link String}</li>
	 * <li>{@link Integer}</li>
	 * <li>{@link Boolean}</li>
	 * </ul>
	 * @param memento
	 * @return a new map of all of the breakpoint attributes from the given memento.
	 * @since 3.5
	 */
	protected Map collectBreakpointProperties(IMemento memento) {
		HashMap map = new HashMap();
		
		//collect attributes from the 'breakpoint' node
		map.put(IImportExportConstants.IE_BP_ENABLED, memento.getBoolean(IImportExportConstants.IE_BP_ENABLED));
		map.put(IImportExportConstants.IE_BP_PERSISTANT, memento.getBoolean(IImportExportConstants.IE_BP_PERSISTANT));
		map.put(IImportExportConstants.IE_BP_REGISTERED, memento.getBoolean(IImportExportConstants.IE_BP_REGISTERED));
		
		//collect attributes from the 'marker' node
		IMemento child = memento.getChild(IImportExportConstants.IE_NODE_MARKER);
		map.put(IImportExportConstants.IE_NODE_TYPE, child.getString(IImportExportConstants.IE_NODE_TYPE));
		map.put(IMarker.LINE_NUMBER, child.getInteger(IMarker.LINE_NUMBER));
		
		//copy all the marker attributes to the map
		IMemento[] children = child.getChildren(IImportExportConstants.IE_NODE_ATTRIB);
		for(int i = 0; i < children.length; i++) {
			readAttribute(children[i], map);
		}

		//collect attributes from the 'resource' node
		child = memento.getChild(IImportExportConstants.IE_NODE_RESOURCE);
		map.put(IImportExportConstants.IE_NODE_PATH, child.getString(IImportExportConstants.IE_NODE_PATH));
		return map;
	}
	
	/**
	 * Collects the 'name' and 'value' key / attribute from the given memento and places it in the specified map
	 * @param memento
	 * @param map
	 */
	private void readAttribute(IMemento memento, Map map) {
		String name = memento.getString(IImportExportConstants.IE_NODE_NAME), 
		   	   value = memento.getString(IImportExportConstants.IE_NODE_VALUE);
		if (value != null && name != null) {
			if (name.equals(IInternalDebugUIConstants.WORKING_SET_NAME)) {
				fCurrentWorkingSetProperty = value;
			}
			Object val = value;
			try {
				val = Integer.valueOf(value);
			} catch (NumberFormatException e) {
				if (value.equalsIgnoreCase("false") || value.equalsIgnoreCase("true")) { //$NON-NLS-1$ //$NON-NLS-2$
					val = Boolean.valueOf(value);
				}
			}
			if(val != null) {
				map.put(name, val);
			}
		}
	}
	
	/**
	 * restores all of the attributes back into the given marker, recreates the breakpoint in the
	 * breakpoint manager, and optionally recreates any working set(s) the breakpoint belongs to.
	 * @param marker
	 * @param attributes
	 * @param participants
	 * @since 3.5
	 */
	protected void restoreBreakpoint(IMarker marker, final Map attributes, IBreakpointImportParticipant[] participants) {
		String key = null;
		for(Iterator iter = attributes.keySet().iterator(); iter.hasNext();) {
			key = (String) iter.next();
			try {
				marker.setAttribute(key, attributes.get(key));
			}
			catch(CoreException ce) {}
		}
		try {
			// create the breakpoint
			IBreakpoint breakpoint = fManager.createBreakpoint(marker);
			breakpoint.setEnabled(((Boolean)attributes.get(IImportExportConstants.IE_BP_ENABLED)).booleanValue());
			breakpoint.setPersisted(((Boolean)attributes.get(IImportExportConstants.IE_BP_PERSISTANT)).booleanValue());
			breakpoint.setRegistered(((Boolean)attributes.get(IImportExportConstants.IE_BP_REGISTERED)).booleanValue());
			fAdded.add(breakpoint);
			if (fCreateWorkingSets && fCurrentWorkingSetProperty != null) {
				String[] names = fCurrentWorkingSetProperty.split("\\" + IImportExportConstants.DELIMITER); //$NON-NLS-1$
				for (int m = 1; m < names.length; m++) {
					createWorkingSet(names[m], breakpoint);
				}
			}
			if(participants != null) {
				for(int i = 0; i < participants.length; i++) {
					participants[i].verify(breakpoint);
				}
			}
		}
		catch(CoreException ce) {}
	}
	
	/**
	 * Returns the breakpoints that were imported by this operation, possibly
	 * an empty list. 
	 * 
	 * @return breakpoints imported by this operation
	 * @since 3.5
	 */
	public IBreakpoint[] getImportedBreakpoints() {
		return (IBreakpoint[])fAdded.toArray(new IBreakpoint[fAdded.size()]);
	}

	/**
	 * Creates a working set and sets the values
	 * @param breakpoint the restored breakpoint to add to the new working set
	 */
	private void createWorkingSet(String setname, IAdaptable element) {
		IWorkingSetManager wsmanager = PlatformUI.getWorkbench().getWorkingSetManager();
		IWorkingSet set = wsmanager.getWorkingSet(setname);
		if (set == null) {
			set = wsmanager.createWorkingSet(setname, new IAdaptable[] {});
			set.setId(IDebugUIConstants.BREAKPOINT_WORKINGSET_ID);
			wsmanager.addWorkingSet(set);
		}
		if (!setContainsBreakpoint(set, (IBreakpoint) element)) {
			IAdaptable[] elements = set.getElements();
			IAdaptable[] newElements = new IAdaptable[elements.length + 1];
			newElements[newElements.length - 1] = element;
			System.arraycopy(elements, 0, newElements, 0, elements.length);
			set.setElements(newElements);
		}
	}

	/**
	 * Method to ensure markers and breakpoints are not both added to the working set
	 * @param set the set to check
	 * @param breakpoint the breakpoint to check for existence
	 * @return true if it is present false otherwise
	 */
	private boolean setContainsBreakpoint(IWorkingSet set, IBreakpoint breakpoint) {
		IAdaptable[] elements = set.getElements();
		for (int i = 0; i < elements.length; i++) {
			if (elements[i].equals(breakpoint)) {
				return true;
			}
		}
		return false;
	}
}
