/*******************************************************************************
 * Copyright (c) 2006, 2014 Wind River Systems and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Wind River Systems - initial API and implementation
 *     Marc Khouzam (Ericsson) - Enable per-element formatting (Bug 439624)
 *******************************************************************************/
package org.eclipse.cdt.dsf.debug.ui.viewmodel.register;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;

import org.eclipse.cdt.dsf.concurrent.ConfinedToDsfExecutor;
import org.eclipse.cdt.dsf.concurrent.CountingRequestMonitor;
import org.eclipse.cdt.dsf.concurrent.DataRequestMonitor;
import org.eclipse.cdt.dsf.concurrent.DsfRunnable;
import org.eclipse.cdt.dsf.concurrent.IDsfStatusConstants;
import org.eclipse.cdt.dsf.concurrent.ImmediateExecutor;
import org.eclipse.cdt.dsf.concurrent.RequestMonitor;
import org.eclipse.cdt.dsf.datamodel.DMContexts;
import org.eclipse.cdt.dsf.datamodel.IDMContext;
import org.eclipse.cdt.dsf.debug.service.IMemory.IMemoryChangedEvent;
import org.eclipse.cdt.dsf.debug.service.IRegisters;
import org.eclipse.cdt.dsf.debug.service.IRegisters.IBitFieldChangedDMEvent;
import org.eclipse.cdt.dsf.debug.service.IRegisters.IBitFieldDMContext;
import org.eclipse.cdt.dsf.debug.service.IRegisters.IBitFieldDMData;
import org.eclipse.cdt.dsf.debug.service.IRegisters.IMnemonic;
import org.eclipse.cdt.dsf.debug.service.IRegisters.IRegisterChangedDMEvent;
import org.eclipse.cdt.dsf.debug.service.IRegisters.IRegisterDMContext;
import org.eclipse.cdt.dsf.debug.service.IRegisters.IRegisterDMData;
import org.eclipse.cdt.dsf.debug.service.IRegisters.IRegisterGroupDMData;
import org.eclipse.cdt.dsf.debug.service.IRunControl.ISuspendedDMEvent;
import org.eclipse.cdt.dsf.debug.ui.viewmodel.ErrorLabelForeground;
import org.eclipse.cdt.dsf.debug.ui.viewmodel.ErrorLabelText;
import org.eclipse.cdt.dsf.debug.ui.viewmodel.IDebugVMConstants;
import org.eclipse.cdt.dsf.debug.ui.viewmodel.expression.AbstractExpressionVMNode;
import org.eclipse.cdt.dsf.debug.ui.viewmodel.numberformat.FormattedValueLabelText;
import org.eclipse.cdt.dsf.debug.ui.viewmodel.numberformat.FormattedValueRetriever;
import org.eclipse.cdt.dsf.debug.ui.viewmodel.numberformat.IFormattedValueVMContext;
import org.eclipse.cdt.dsf.debug.ui.viewmodel.register.RegisterBitFieldCellModifier.BitFieldEditorStyle;
import org.eclipse.cdt.dsf.debug.ui.viewmodel.update.ElementFormatEvent;
import org.eclipse.cdt.dsf.debug.ui.viewmodel.variable.VariableLabelFont;
import org.eclipse.cdt.dsf.internal.ui.DsfUIPlugin;
import org.eclipse.cdt.dsf.service.DsfSession;
import org.eclipse.cdt.dsf.ui.concurrent.ViewerDataRequestMonitor;
import org.eclipse.cdt.dsf.ui.viewmodel.VMDelta;
import org.eclipse.cdt.dsf.ui.viewmodel.datamodel.AbstractDMVMProvider;
import org.eclipse.cdt.dsf.ui.viewmodel.datamodel.IDMVMContext;
import org.eclipse.cdt.dsf.ui.viewmodel.properties.IElementPropertiesProvider;
import org.eclipse.cdt.dsf.ui.viewmodel.properties.IPropertiesUpdate;
import org.eclipse.cdt.dsf.ui.viewmodel.properties.LabelAttribute;
import org.eclipse.cdt.dsf.ui.viewmodel.properties.LabelBackground;
import org.eclipse.cdt.dsf.ui.viewmodel.properties.LabelColumnInfo;
import org.eclipse.cdt.dsf.ui.viewmodel.properties.LabelForeground;
import org.eclipse.cdt.dsf.ui.viewmodel.properties.LabelImage;
import org.eclipse.cdt.dsf.ui.viewmodel.properties.LabelText;
import org.eclipse.cdt.dsf.ui.viewmodel.properties.PropertiesBasedLabelProvider;
import org.eclipse.cdt.dsf.ui.viewmodel.properties.VMDelegatingPropertiesUpdate;
import org.eclipse.cdt.dsf.ui.viewmodel.update.ICachingVMProvider;
import org.eclipse.cdt.dsf.ui.viewmodel.update.StaleDataLabelBackground;
import org.eclipse.cdt.dsf.ui.viewmodel.update.StaleDataLabelForeground;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.model.IExpression;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IChildrenUpdate;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IElementCompareRequest;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IElementEditor;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IElementLabelProvider;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IElementMementoProvider;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IElementMementoRequest;
import org.eclipse.debug.internal.ui.viewers.model.provisional.ILabelUpdate;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IModelDelta;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IPresentationContext;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.debug.ui.IDebugModelPresentation;
import org.eclipse.debug.ui.IDebugUIConstants;
import org.eclipse.debug.ui.actions.IWatchExpressionFactoryAdapter2;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.jface.viewers.CellEditor;
import org.eclipse.jface.viewers.ComboBoxCellEditor;
import org.eclipse.jface.viewers.ICellModifier;
import org.eclipse.jface.viewers.TextCellEditor;
import org.eclipse.jface.viewers.TreePath;
import org.eclipse.swt.widgets.Composite;

public class RegisterBitFieldVMNode extends AbstractExpressionVMNode
		implements IElementEditor, IElementLabelProvider, IElementMementoProvider, IElementPropertiesProvider {
	/**
	 * @since 2.0
	 */
	private static final String PROP_BITFIELD_SHOW_TYPE_NAMES = "bitfield_show_type_names"; //$NON-NLS-1$

	protected class BitFieldVMC extends DMVMContext implements IFormattedValueVMContext {
		private IExpression fExpression;

		public BitFieldVMC(IDMContext dmc) {
			super(dmc);
		}

		public void setExpression(IExpression expression) {
			fExpression = expression;
		}

		@Override
		@SuppressWarnings({ "rawtypes", "unchecked" })
		public Object getAdapter(Class adapter) {
			if (fExpression != null && adapter.isAssignableFrom(fExpression.getClass())) {
				return fExpression;
			} else if (adapter.isAssignableFrom(IWatchExpressionFactoryAdapter2.class)) {
				return getWatchExpressionFactory();
			} else {
				return super.getAdapter(adapter);
			}
		}

		@Override
		public boolean equals(Object other) {
			if (other instanceof BitFieldVMC && super.equals(other)) {
				BitFieldVMC otherBitField = (BitFieldVMC) other;
				return (otherBitField.fExpression == null && fExpression == null)
						|| (otherBitField.fExpression != null && otherBitField.fExpression.equals(fExpression));
			}
			return false;
		}

		@Override
		public int hashCode() {
			return super.hashCode() + (fExpression != null ? fExpression.hashCode() : 0);
		}
	}

	protected class BitFieldExpressionFactory implements IWatchExpressionFactoryAdapter2 {

		@Override
		public boolean canCreateWatchExpression(Object element) {
			return element instanceof BitFieldVMC;
		}

		/**
		 * Expected format: GRP( GroupName ).REG( RegisterName ).BFLD( BitFieldname )
		 */
		@Override
		public String createWatchExpression(Object element) throws CoreException {
			IRegisterGroupDMData groupData = getSyncRegisterDataAccess().getRegisterGroupDMData(element);
			IRegisterDMData registerData = getSyncRegisterDataAccess().getRegisterDMData(element);
			IBitFieldDMData bitFieldData = getSyncRegisterDataAccess().getBitFieldDMData(element);

			if (groupData != null && registerData != null && bitFieldData != null) {
				StringBuffer exprBuf = new StringBuffer();

				exprBuf.append("GRP( "); //$NON-NLS-1$
				exprBuf.append(groupData.getName());
				exprBuf.append(" )"); //$NON-NLS-1$
				exprBuf.append(".REG( "); //$NON-NLS-1$
				exprBuf.append(registerData.getName());
				exprBuf.append(" )"); //$NON-NLS-1$
				exprBuf.append(".BFLD( "); //$NON-NLS-1$
				exprBuf.append(bitFieldData.getName());
				exprBuf.append(" )"); //$NON-NLS-1$

				return exprBuf.toString();
			}

			return null;
		}
	}

	private SyncRegisterDataAccess fSyncRegisterDataAccess = null;
	protected IWatchExpressionFactoryAdapter2 fBitFieldExpressionFactory = null;

	/**
	 * The label provider delegate.  This VM node will delegate label updates to this provider
	 * which can be created by sub-classes.
	 *
	 * @since 2.0
	 */
	private IElementLabelProvider fLabelProvider;

	/**
	 * Retriever for formatted values configured for this VM node.
	 * @since 2.2
	 */
	private final FormattedValueRetriever fFormattedValueRetriever;

	public RegisterBitFieldVMNode(AbstractDMVMProvider provider, DsfSession session, SyncRegisterDataAccess access) {
		super(provider, session, IBitFieldDMContext.class);
		fSyncRegisterDataAccess = access;
		fLabelProvider = createLabelProvider();
		fFormattedValueRetriever = new FormattedValueRetriever(this, session, IRegisters.class,
				IBitFieldDMContext.class);
	}

	@Override
	public void dispose() {
		super.dispose();
		fFormattedValueRetriever.dispose();
	}

	@Override
	public String toString() {
		return "RegisterBitFieldVMNode(" + getSession().getId() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
	}

	private Object[] constructTypeObjects(Map<String, Object> properties) {
		int readAttr = 0;
		if (Boolean.TRUE.equals(properties.get(IRegisterVMConstants.PROP_IS_READABLE))) {
			readAttr = 1;
		} else if (Boolean.TRUE.equals(properties.get(IRegisterVMConstants.PROP_IS_READONCE))) {
			readAttr = 2;
		}

		int writeAttr = 0;
		if (Boolean.TRUE.equals(properties.get(IRegisterVMConstants.PROP_IS_WRITEABLE))) {
			writeAttr = 1;
		} else if (Boolean.TRUE.equals(properties.get(IRegisterVMConstants.PROP_IS_WRITEONCE))) {
			writeAttr = 2;
		}

		Object[] messageAttrs = new Object[] { readAttr, writeAttr };

		return messageAttrs;
	}

	/**
	 * Creates the label provider delegate.  This VM node will delegate label
	 * updates to this provider which can be created by sub-classes.
	 *
	 * @return Returns the label provider for this node.
	 *
	 * @since 2.0
	 */
	protected IElementLabelProvider createLabelProvider() {
		PropertiesBasedLabelProvider provider = new PropertiesBasedLabelProvider();

		// The name column consists of the bit field name.
		provider.setColumnInfo(IDebugVMConstants.COLUMN_ID__NAME,
				new LabelColumnInfo(new LabelAttribute[] {
						new LabelText(MessagesForRegisterVM.RegisterBitFieldVMNode_Name_column__text_format,
								new String[] { PROP_NAME }),
						new LabelImage(DebugUITools.getImageDescriptor(IDebugUIConstants.IMG_OBJS_REGISTER)),
						new StaleDataLabelForeground(), new VariableLabelFont(), }));

		// The description column contains a brief description of the bit field.
		provider.setColumnInfo(IDebugVMConstants.COLUMN_ID__DESCRIPTION,
				new LabelColumnInfo(new LabelAttribute[] {
						new LabelText(MessagesForRegisterVM.RegisterBitFieldVMNode_Description_column__text_format,
								new String[] { IRegisterVMConstants.PROP_DESCRIPTION }),
						new StaleDataLabelForeground(), new VariableLabelFont(), }));

		// In the type column add information about bit field read/write/fload flags.
		provider.setColumnInfo(IDebugVMConstants.COLUMN_ID__TYPE,
				new LabelColumnInfo(new LabelAttribute[] {
						new LabelText(MessagesForRegisterVM.RegisterBitFieldVMNode_Type_column__text_format,
								new String[] { IRegisterVMConstants.PROP_IS_READABLE,
										IRegisterVMConstants.PROP_IS_READONCE, IRegisterVMConstants.PROP_IS_WRITEABLE,
										IRegisterVMConstants.PROP_IS_WRITEONCE }) {
							@Override
							public void updateAttribute(ILabelUpdate update, int columnIndex, IStatus status,
									Map<String, Object> properties) {
								Object[] messageAttrs = constructTypeObjects(properties);
								try {
									update.setLabel(getMessageFormat().format(messageAttrs, new StringBuffer(), null)
											.toString(), columnIndex);
								} catch (IllegalArgumentException e) {
									update.setStatus(new Status(IStatus.ERROR, DsfUIPlugin.PLUGIN_ID, 0,
											"Failed formatting a message for column " + columnIndex + ", for update " //$NON-NLS-1$//$NON-NLS-2$
													+ update,
											e));
								}
							}
						}, new StaleDataLabelForeground(), new VariableLabelFont(), }));

		// Value column shows the value in the active value format, followed by the active mnemonic if one is
		// available.
		//
		// In case of error, show the error message in the value column (instead of the usual "...".  This is needed
		// for the expressions view, where an invalid expression entered by the user is a normal use case.
		//
		// For changed value high-lighting check the value in the active format.  But if the format itself has changed,
		// ignore the value change.
		provider.setColumnInfo(IDebugVMConstants.COLUMN_ID__VALUE, new LabelColumnInfo(new LabelAttribute[] {
				new FormattedValueLabelText(
						MessagesForRegisterVM.RegisterBitFieldVMNode_Value_column__With_mnemonic__text_format,
						new String[] { IDebugVMConstants.PROP_FORMATTED_VALUE_ACTIVE_FORMAT_VALUE,
								IRegisterVMConstants.PROP_CURRENT_MNEMONIC_LONG_NAME }),
				new FormattedValueLabelText(), new ErrorLabelText(), new ErrorLabelForeground(), new LabelBackground(
						DebugUITools.getPreferenceColor(IDebugUIConstants.PREF_CHANGED_VALUE_BACKGROUND).getRGB()) {
					{
						setPropertyNames(new String[] { IDebugVMConstants.PROP_FORMATTED_VALUE_ACTIVE_FORMAT_VALUE,
								ICachingVMProvider.PROP_IS_CHANGED_PREFIX
										+ IDebugVMConstants.PROP_FORMATTED_VALUE_ACTIVE_FORMAT_VALUE,
								IDebugVMConstants.PROP_FORMATTED_VALUE_ACTIVE_FORMAT,
								ICachingVMProvider.PROP_IS_CHANGED_PREFIX
										+ IDebugVMConstants.PROP_FORMATTED_VALUE_ACTIVE_FORMAT });
					}

					@Override
					public boolean isEnabled(IStatus status, java.util.Map<String, Object> properties) {
						Boolean activeFormatChanged = (Boolean) properties.get(ICachingVMProvider.PROP_IS_CHANGED_PREFIX
								+ IDebugVMConstants.PROP_FORMATTED_VALUE_ACTIVE_FORMAT);
						Boolean activeChanged = (Boolean) properties.get(ICachingVMProvider.PROP_IS_CHANGED_PREFIX
								+ IDebugVMConstants.PROP_FORMATTED_VALUE_ACTIVE_FORMAT_VALUE);
						return Boolean.TRUE.equals(activeChanged) && !Boolean.TRUE.equals(activeFormatChanged);
					}
				}, new StaleDataLabelForeground(), new VariableLabelFont(), }));

		// Expression column is visible only in the expressions view.  It shows the expression string that the user
		// entered.  Expression column images are the same as for the name column.
		provider.setColumnInfo(IDebugVMConstants.COLUMN_ID__EXPRESSION,
				new LabelColumnInfo(new LabelAttribute[] {
						new LabelText(MessagesForRegisterVM.RegisterBitFieldVMNode_Expression_column__text_format,
								new String[] { PROP_ELEMENT_EXPRESSION }),
						new LabelImage(DebugUITools.getImageDescriptor(IDebugUIConstants.IMG_OBJS_REGISTER)),
						new StaleDataLabelForeground(), new VariableLabelFont(), }));

		provider.setColumnInfo(PropertiesBasedLabelProvider.ID_COLUMN_NO_COLUMNS,
				new LabelColumnInfo(new LabelAttribute[] { new FormattedValueLabelText(
						MessagesForRegisterVM.RegisterBitFieldVMNode_No_columns__With_mnemonic__text_format,
						new String[] { PROP_NAME, IDebugVMConstants.PROP_FORMATTED_VALUE_ACTIVE_FORMAT_VALUE,
								IRegisterVMConstants.PROP_CURRENT_MNEMONIC_LONG_NAME }) {
					@Override
					public boolean isEnabled(IStatus status, Map<String, Object> properties) {
						Boolean showTypeNames = (Boolean) properties.get(PROP_BITFIELD_SHOW_TYPE_NAMES);
						return showTypeNames != null && !showTypeNames.booleanValue()
								&& super.isEnabled(status, properties);
					}
				}, new FormattedValueLabelText(
						MessagesForRegisterVM.RegisterBitFieldVMNode_No_columns__With_mnemonic__text_format_with_type,
						new String[] { PROP_NAME, IDebugVMConstants.PROP_FORMATTED_VALUE_ACTIVE_FORMAT_VALUE,
								IRegisterVMConstants.PROP_CURRENT_MNEMONIC_LONG_NAME,
								IRegisterVMConstants.PROP_IS_READABLE, IRegisterVMConstants.PROP_IS_READONCE,
								IRegisterVMConstants.PROP_IS_WRITEABLE, IRegisterVMConstants.PROP_IS_WRITEONCE,
								PROP_BITFIELD_SHOW_TYPE_NAMES }) {
					@Override
					public void updateAttribute(ILabelUpdate update, int columnIndex, IStatus status,
							Map<String, Object> properties) {
						Object[] messageAttrs = constructTypeObjects(properties);
						Object[] combinedAttrs = new Object[messageAttrs.length + 3];
						combinedAttrs[0] = super.getPropertyValue(PROP_NAME, status, properties);
						combinedAttrs[1] = super.getPropertyValue(
								IDebugVMConstants.PROP_FORMATTED_VALUE_ACTIVE_FORMAT_VALUE, status, properties);
						combinedAttrs[2] = super.getPropertyValue(IRegisterVMConstants.PROP_CURRENT_MNEMONIC_LONG_NAME,
								status, properties);
						for (int idx = 0; idx < messageAttrs.length; idx++) {
							combinedAttrs[idx + 3] = messageAttrs[idx];
						}

						try {
							update.setLabel(
									getMessageFormat().format(combinedAttrs, new StringBuffer(), null).toString(),
									columnIndex);
						} catch (IllegalArgumentException e) {
							update.setStatus(new Status(IStatus.ERROR, DsfUIPlugin.PLUGIN_ID, 0,
									"Failed formatting a message for column " + columnIndex + ", for update " + update, //$NON-NLS-1$//$NON-NLS-2$
									e));
						}
					}

					@Override
					public boolean isEnabled(IStatus status, Map<String, Object> properties) {
						Boolean showTypeNames = (Boolean) properties.get(PROP_BITFIELD_SHOW_TYPE_NAMES);
						return showTypeNames != null && showTypeNames.booleanValue()
								&& super.isEnabled(status, properties);
					}
				}, new FormattedValueLabelText(MessagesForRegisterVM.RegisterBitFieldVMNode_No_columns__text_format,
						new String[] { PROP_NAME, IDebugVMConstants.PROP_FORMATTED_VALUE_ACTIVE_FORMAT_VALUE }) {
					@Override
					public boolean isEnabled(IStatus status, Map<String, Object> properties) {
						Boolean showTypeNames = (Boolean) properties.get(PROP_BITFIELD_SHOW_TYPE_NAMES);
						return showTypeNames != null && !showTypeNames.booleanValue()
								&& super.isEnabled(status, properties);
					}
				}, new FormattedValueLabelText(
						MessagesForRegisterVM.RegisterBitFieldVMNode_No_columns__text_format_with_type,
						new String[] { PROP_NAME, IDebugVMConstants.PROP_FORMATTED_VALUE_ACTIVE_FORMAT_VALUE,
								IRegisterVMConstants.PROP_IS_READABLE, IRegisterVMConstants.PROP_IS_READONCE,
								IRegisterVMConstants.PROP_IS_WRITEABLE, IRegisterVMConstants.PROP_IS_WRITEONCE,
								PROP_BITFIELD_SHOW_TYPE_NAMES }) {
					@Override
					public void updateAttribute(ILabelUpdate update, int columnIndex, IStatus status,
							Map<String, Object> properties) {
						Object[] messageAttrs = constructTypeObjects(properties);
						Object[] combinedAttrs = new Object[messageAttrs.length + 2];
						combinedAttrs[0] = super.getPropertyValue(PROP_NAME, status, properties);
						combinedAttrs[1] = super.getPropertyValue(
								IDebugVMConstants.PROP_FORMATTED_VALUE_ACTIVE_FORMAT_VALUE, status, properties);
						for (int idx = 0; idx < messageAttrs.length; idx++) {
							combinedAttrs[idx + 2] = messageAttrs[idx];
						}

						try {
							update.setLabel(
									getMessageFormat().format(combinedAttrs, new StringBuffer(), null).toString(),
									columnIndex);
						} catch (IllegalArgumentException e) {
							update.setStatus(new Status(IStatus.ERROR, DsfUIPlugin.PLUGIN_ID, 0,
									"Failed formatting a message for column " + columnIndex + ", for update " + update, //$NON-NLS-1$//$NON-NLS-2$
									e));
						}
					}

					@Override
					public boolean isEnabled(IStatus status, Map<String, Object> properties) {
						Boolean showTypeNames = (Boolean) properties.get(PROP_BITFIELD_SHOW_TYPE_NAMES);
						return showTypeNames != null && showTypeNames.booleanValue()
								&& super.isEnabled(status, properties);
					}
				}, new ErrorLabelText(MessagesForRegisterVM.RegisterBitFieldVMNode_No_columns__Error__text_format,
						new String[] { PROP_NAME }),
						new LabelImage(DebugUITools.getImageDescriptor(IDebugUIConstants.IMG_OBJS_REGISTER)),
						new LabelForeground(DebugUITools
								.getPreferenceColor(IDebugUIConstants.PREF_CHANGED_DEBUG_ELEMENT_COLOR).getRGB()) {
							{
								setPropertyNames(
										new String[] { IDebugVMConstants.PROP_FORMATTED_VALUE_ACTIVE_FORMAT_VALUE,
												ICachingVMProvider.PROP_IS_CHANGED_PREFIX
														+ IDebugVMConstants.PROP_FORMATTED_VALUE_ACTIVE_FORMAT_VALUE,
												IDebugVMConstants.PROP_FORMATTED_VALUE_ACTIVE_FORMAT,
												ICachingVMProvider.PROP_IS_CHANGED_PREFIX
														+ IDebugVMConstants.PROP_FORMATTED_VALUE_ACTIVE_FORMAT });
							}

							@Override
							public boolean isEnabled(IStatus status, java.util.Map<String, Object> properties) {
								Boolean activeFormatChanged = (Boolean) properties
										.get(ICachingVMProvider.PROP_IS_CHANGED_PREFIX
												+ IDebugVMConstants.PROP_FORMATTED_VALUE_ACTIVE_FORMAT);
								Boolean activeChanged = (Boolean) properties
										.get(ICachingVMProvider.PROP_IS_CHANGED_PREFIX
												+ IDebugVMConstants.PROP_FORMATTED_VALUE_ACTIVE_FORMAT_VALUE);
								return Boolean.TRUE.equals(activeChanged) && !Boolean.TRUE.equals(activeFormatChanged);
							}
						}, new StaleDataLabelBackground(), new VariableLabelFont(), }));

		return provider;
	}

	/**
	 * @since 1.1
	 */
	public SyncRegisterDataAccess getSyncRegisterDataAccess() {
		return fSyncRegisterDataAccess;
	}

	/**
	 * @since 1.1
	 */
	public IWatchExpressionFactoryAdapter2 getWatchExpressionFactory() {
		if (fBitFieldExpressionFactory == null) {
			fBitFieldExpressionFactory = new BitFieldExpressionFactory();
		}
		return fBitFieldExpressionFactory;
	}

	@Override
	public void update(final ILabelUpdate[] updates) {
		fLabelProvider.update(updates);
	}

	/**
	 * Update the variable view properties.  The formatted values need to be
	 * updated in the VM executor thread while the rest of the properties is
	 * updated in the service session's executor thread.  The implementation
	 * splits the handling of the updates to accomplish that.
	 *
	 * @see IElementPropertiesProvider#update(IPropertiesUpdate[])
	 *
	 * @since 2.0
	 */
	@Override
	public void update(final IPropertiesUpdate[] updates) {
		final CountingRequestMonitor countingRm = new CountingRequestMonitor(ImmediateExecutor.getInstance(), null) {
			@Override
			protected void handleCompleted() {
				for (int i = 0; i < updates.length; i++) {
					updates[i].done();
				}
			}
		};
		int count = 0;

		fFormattedValueRetriever.update(updates, countingRm);
		count++;

		final IPropertiesUpdate[] subUpdates = new IPropertiesUpdate[updates.length];
		for (int i = 0; i < updates.length; i++) {
			final IPropertiesUpdate update = updates[i];
			subUpdates[i] = new VMDelegatingPropertiesUpdate(update, countingRm);
			count++;
		}
		countingRm.setDoneCount(count);

		try {
			getSession().getExecutor().execute(new DsfRunnable() {
				@Override
				public void run() {
					updatePropertiesInSessionThread(subUpdates);
				}
			});
		} catch (RejectedExecutionException e) {
			for (IPropertiesUpdate subUpdate : subUpdates) {
				subUpdate.setStatus(new Status(IStatus.ERROR, DsfUIPlugin.PLUGIN_ID, IDsfStatusConstants.REQUEST_FAILED,
						"Session executor shut down " + getSession().getExecutor(), e)); //$NON-NLS-1$
				subUpdate.done();
			}
		}
	}

	//
	//  @param return-value Boolean.TRUE  --> Show Types ICON is     selected/depressed
	//  @param return-value Boolean.FALSE --> Show Types ICON is not selected/depressed
	//
	private Boolean getShowTypeNamesState(IPresentationContext context) {
		Boolean attribute = (Boolean) context.getProperty(IDebugModelPresentation.DISPLAY_VARIABLE_TYPE_NAMES);

		if (attribute != null) {
			return attribute;
		}

		return Boolean.FALSE;
	}

	/**
	 * @since 2.0
	 */
	@ConfinedToDsfExecutor("getSession().getExecutor()")
	protected void updatePropertiesInSessionThread(final IPropertiesUpdate[] updates) {
		IRegisters service = getServicesTracker().getService(IRegisters.class, null);

		final CountingRequestMonitor countingRm = new CountingRequestMonitor(ImmediateExecutor.getInstance(), null) {
			@Override
			protected void handleCompleted() {
				for (final IPropertiesUpdate update : updates) {
					update.done();
				}
			}
		};
		int count = 0;

		for (final IPropertiesUpdate update : updates) {
			IExpression expression = (IExpression) DebugPlugin.getAdapter(update.getElement(), IExpression.class);
			if (expression != null) {
				update.setProperty(AbstractExpressionVMNode.PROP_ELEMENT_EXPRESSION, expression.getExpressionText());
			}

			// Capture the current "Show Type Names" ICON state in case there are no columns.
			if (update.getProperties().contains(PROP_BITFIELD_SHOW_TYPE_NAMES)) {
				update.setProperty(PROP_BITFIELD_SHOW_TYPE_NAMES,
						getShowTypeNamesState(update.getPresentationContext()));
			}

			IBitFieldDMContext dmc = findDmcInPath(update.getViewerInput(), update.getElementPath(),
					IBitFieldDMContext.class);
			if (dmc == null || service == null) {
				handleFailedUpdate(update);
				continue;
			}

			service.getBitFieldData(dmc,
					// Use the ViewerDataRequestMonitor in order to propagate the update's cancel request. Use an immediate
					// executor to avoid the possibility of a rejected execution exception.
					new ViewerDataRequestMonitor<IBitFieldDMData>(getSession().getExecutor(), update) {
						@Override
						protected void handleCompleted() {
							if (isSuccess()) {
								fillBitFieldDataProperties(update, getData());
							} else {
								update.setStatus(getStatus());
							}
							countingRm.done();
						}
					});
			count++;
		}
		countingRm.setDoneCount(count);
	}

	/**
	 * @since 2.0
	 */
	@ConfinedToDsfExecutor("getSession().getExecutor()")
	protected void fillBitFieldDataProperties(IPropertiesUpdate update, IBitFieldDMData data) {
		update.setProperty(PROP_NAME, data.getName());
		update.setProperty(IRegisterVMConstants.PROP_DESCRIPTION, data.getDescription());
		update.setProperty(IRegisterVMConstants.PROP_IS_READABLE, data.isReadable());
		update.setProperty(IRegisterVMConstants.PROP_IS_READONCE, data.isReadOnce());
		update.setProperty(IRegisterVMConstants.PROP_IS_WRITEABLE, data.isWriteable());
		update.setProperty(IRegisterVMConstants.PROP_IS_WRITEONCE, data.isWriteOnce());
		update.setProperty(IRegisterVMConstants.PROP_HAS_SIDE_EFFECTS, data.hasSideEffects());
		update.setProperty(IRegisterVMConstants.PROP_IS_ZERO_BIT_LEFT_MOST, data.isZeroBitLeftMost());
		update.setProperty(IRegisterVMConstants.PROP_IS_ZERO_BASED_NUMBERING, data.isZeroBasedNumbering());
		IMnemonic mnemonic = data.getCurrentMnemonicValue();
		if (mnemonic != null) {
			update.setProperty(IRegisterVMConstants.PROP_CURRENT_MNEMONIC_LONG_NAME, mnemonic.getLongName());
			update.setProperty(IRegisterVMConstants.PROP_CURRENT_MNEMONIC_SHORT_NAME, mnemonic.getShortName());
		}

		/*
		 * If this node has an expression then it has already been filled in by the higher
		 * level logic. If not then we need to supply something.  In the  previous version
		 * ( pre-property based ) we supplied the name. So we will do that here also.
		 */
		IExpression expression = (IExpression) DebugPlugin.getAdapter(update.getElement(), IExpression.class);
		if (expression == null) {
			update.setProperty(AbstractExpressionVMNode.PROP_ELEMENT_EXPRESSION, data.getName());
		}
	}

	@Override
	protected void updateElementsInSessionThread(final IChildrenUpdate update) {
		final IRegisterDMContext regDmc = findDmcInPath(update.getViewerInput(), update.getElementPath(),
				IRegisterDMContext.class);

		if (regDmc == null) {
			handleFailedUpdate(update);
			return;
		}

		IRegisters regService = getServicesTracker().getService(IRegisters.class);

		if (regService == null) {
			handleFailedUpdate(update);
			return;
		}

		regService.getBitFields(regDmc,
				new ViewerDataRequestMonitor<IBitFieldDMContext[]>(getSession().getExecutor(), update) {
					@Override
					protected void handleFailure() {
						handleFailedUpdate(update);
					}

					@Override
					protected void handleSuccess() {
						fillUpdateWithVMCs(update, getData());
						update.done();
					}
				});
	}

	@Override
	protected IDMVMContext createVMContext(IDMContext dmc) {
		return new BitFieldVMC(dmc);
	}

	@Override
	public int getDeltaFlags(Object e) {
		if (e instanceof ISuspendedDMEvent || e instanceof IMemoryChangedEvent || e instanceof IRegisterChangedDMEvent
				|| (e instanceof PropertyChangeEvent && ((PropertyChangeEvent) e)
						.getProperty() == IDebugVMConstants.PROP_FORMATTED_VALUE_FORMAT_PREFERENCE)) {
			return IModelDelta.CONTENT;
		}

		if (e instanceof IBitFieldChangedDMEvent) {
			return IModelDelta.STATE;
		}

		return IModelDelta.NO_CHANGE;
	}

	@Override
	public void buildDelta(Object e, VMDelta parentDelta, int nodeOffset, RequestMonitor rm) {
		// The following events can affect any bit field's values,
		// refresh the contents of the parent element (i.e. all the registers).
		if (e instanceof ISuspendedDMEvent || e instanceof IMemoryChangedEvent || e instanceof IRegisterChangedDMEvent
				|| (e instanceof PropertyChangeEvent && ((PropertyChangeEvent) e)
						.getProperty() == IDebugVMConstants.PROP_FORMATTED_VALUE_FORMAT_PREFERENCE)) {
			parentDelta.setFlags(parentDelta.getFlags() | IModelDelta.CONTENT);
		}

		if (e instanceof IBitFieldChangedDMEvent) {
			// Create a delta indicating that the value of bit field has changed.
			parentDelta.addNode(createVMContext(((IBitFieldChangedDMEvent) e).getDMContext()), IModelDelta.STATE);
		}

		rm.done();
	}

	@Override
	public CellEditor getCellEditor(IPresentationContext context, String columnId, Object element, Composite parent) {

		if (IDebugVMConstants.COLUMN_ID__VALUE.equals(columnId)) {
			/*
			 *   In order to decide what kind of editor to present we need to know if there are
			 *   mnemonics which can be used to represent the values. If there are then we will
			 *   create a Combo editor for them. Otherwise we will just make a normal text cell
			 *   editor.  If there are bit groups then the modifier will check the size of  the
			 *   value being entered.
			 */
			IBitFieldDMData bitFieldData = getSyncRegisterDataAccess().readBitField(element);

			if (bitFieldData != null && bitFieldData.isWriteable()) {

				IMnemonic[] mnemonics = bitFieldData.getMnemonics();

				if (mnemonics != null && mnemonics.length != 0) {

					/*
					 *   Create the list of readable dropdown selections.
					 */
					String[] StringValues = new String[mnemonics.length];

					int idx = 0;
					for (IMnemonic mnemonic : mnemonics) {
						StringValues[idx++] = mnemonic.getLongName();
					}

					/*
					 *  Not we are complex COMBO and return the right editor.
					 */
					return new ComboBoxCellEditor(parent, StringValues);
				} else {
					/*
					 *  Text editor even if we need to clamp the value entered.
					 */
					return new TextCellEditor(parent);
				}
			}
		} else if (IDebugVMConstants.COLUMN_ID__EXPRESSION.equals(columnId)) {
			return new TextCellEditor(parent);
		}
		return null;
	}

	@Override
	public ICellModifier getCellModifier(IPresentationContext context, Object element) {

		/*
		 *   In order to decide what kind of modifier to present we need to know if there
		 *   are mnemonics which can be used to represent the values.
		 */
		IBitFieldDMData bitFieldData = getSyncRegisterDataAccess().readBitField(element);

		if (bitFieldData != null && bitFieldData.isWriteable()) {

			IMnemonic[] mnemonics = bitFieldData.getMnemonics();

			if (mnemonics != null && mnemonics.length != 0) {
				/*
				 *  Note we are complex COMBO and return the right editor.
				 */
				return new RegisterBitFieldCellModifier(getDMVMProvider(), BitFieldEditorStyle.BITFIELDCOMBO,
						getSyncRegisterDataAccess());
			} else {
				/*
				 *  Text editor even if we need to clamp the value entered.
				 */
				return new RegisterBitFieldCellModifier(getDMVMProvider(), BitFieldEditorStyle.BITFIELDTEXT,
						getSyncRegisterDataAccess());
			}
		} else {
			return null;
		}
	}

	/**
	 * Expected format: GRP( GroupName ).REG( RegisterName ).BFLD( BitFieldname )
	 */

	@Override
	public boolean canParseExpression(IExpression expression) {
		return parseExpressionForBitFieldName(expression.getExpressionText()) != null;
	}

	private String parseExpressionForBitFieldName(String expression) {

		if (expression.startsWith("GRP(")) { //$NON-NLS-1$

			/*
			 *  Get the group portion.
			 */
			int startIdx = "GRP(".length(); //$NON-NLS-1$
			int endIdx = expression.indexOf(')', startIdx);
			if (startIdx == -1 || endIdx == -1) {
				return null;
			}
			String remaining = expression.substring(endIdx + 1);
			if (!remaining.startsWith(".REG(")) { //$NON-NLS-1$
				return null;
			}

			/*
			 * Get the register portion.
			 */
			startIdx = ".REG(".length(); //$NON-NLS-1$
			endIdx = remaining.indexOf(')', startIdx);
			if (startIdx == -1 || endIdx == -1) {
				return null;
			}
			remaining = remaining.substring(endIdx + 1);

			/*
			 * Get the bit-field portion.
			 */
			if (!remaining.startsWith(".BFLD(")) { //$NON-NLS-1$
				return null;
			}
			startIdx = ".BFLD(".length(); //$NON-NLS-1$
			endIdx = remaining.indexOf(')', startIdx);
			if (startIdx == -1 || endIdx == -1) {
				return null;
			}
			String bitFieldName = remaining.substring(startIdx, endIdx);

			/*
			 * Make sure there is nothing following. If there is then this
			 * is not a properly formed expression and we do not claim it.
			 */
			remaining = remaining.substring(endIdx + 1);

			if (remaining.length() != 0) {
				return null;
			}

			return bitFieldName.trim();
		}

		return null;
	}

	@Override
	protected void testElementForExpression(Object element, IExpression expression,
			final DataRequestMonitor<Boolean> rm) {
		if (!(element instanceof IDMVMContext)) {
			rm.setStatus(new Status(IStatus.ERROR, DsfUIPlugin.PLUGIN_ID, IDsfStatusConstants.INVALID_HANDLE,
					"Invalid context", null)); //$NON-NLS-1$
			rm.done();
			return;
		}

		final IBitFieldDMContext dmc = DMContexts.getAncestorOfType(((IDMVMContext) element).getDMContext(),
				IBitFieldDMContext.class);
		if (dmc == null) {
			rm.setStatus(new Status(IStatus.ERROR, DsfUIPlugin.PLUGIN_ID, IDsfStatusConstants.INVALID_HANDLE,
					"Invalid context", null)); //$NON-NLS-1$
			rm.done();
			return;
		}

		final String bitFieldName = parseExpressionForBitFieldName(expression.getExpressionText());
		try {
			getSession().getExecutor().execute(new DsfRunnable() {
				@Override
				public void run() {
					IRegisters registersService = getServicesTracker().getService(IRegisters.class);
					if (registersService != null) {
						registersService.getBitFieldData(dmc,
								new DataRequestMonitor<IBitFieldDMData>(ImmediateExecutor.getInstance(), rm) {
									@Override
									protected void handleSuccess() {
										rm.setData(getData().getName().equals(bitFieldName));
										rm.done();
									}
								});
					} else {
						rm.setStatus(new Status(IStatus.WARNING, DsfUIPlugin.PLUGIN_ID,
								IDsfStatusConstants.INVALID_STATE, "Register service not available", null)); //$NON-NLS-1$
						rm.done();
					}
				}
			});
		} catch (RejectedExecutionException e) {
			rm.setStatus(new Status(IStatus.WARNING, DsfUIPlugin.PLUGIN_ID, IDsfStatusConstants.INVALID_STATE,
					"DSF session shut down", null)); //$NON-NLS-1$
			rm.done();
		}
	}

	@Override
	protected void associateExpression(Object element, IExpression expression) {
		if (element instanceof BitFieldVMC) {
			((BitFieldVMC) element).setExpression(expression);
		}
	}

	@Override
	public int getDeltaFlagsForExpression(IExpression expression, Object event) {
		if (event instanceof ISuspendedDMEvent) {
			return IModelDelta.CONTENT;
		}

		if (event instanceof PropertyChangeEvent && ((PropertyChangeEvent) event)
				.getProperty() == IDebugVMConstants.PROP_FORMATTED_VALUE_FORMAT_PREFERENCE) {
			return IModelDelta.CONTENT;
		}

		if (event instanceof IMemoryChangedEvent) {
			return IModelDelta.CONTENT;
		}

		if (event instanceof ElementFormatEvent) {
			int depth = ((ElementFormatEvent) event).getApplyDepth();
			if (depth == 0)
				return IModelDelta.NO_CHANGE;
			if (depth == 1)
				return IModelDelta.STATE;
			return IModelDelta.CONTENT;
		}

		return IModelDelta.NO_CHANGE;
	}

	@Override
	public void buildDeltaForExpression(final IExpression expression, final int elementIdx, final Object event,
			final VMDelta parentDelta, final TreePath path, final RequestMonitor rm) {
		// Always refresh the contents of the view upon suspended event.
		if (event instanceof ISuspendedDMEvent) {
			parentDelta.setFlags(parentDelta.getFlags() | IModelDelta.CONTENT);
		}

		rm.done();
	}

	@Override
	public void buildDeltaForExpressionElement(Object element, int elementIdx, Object event, VMDelta parentDelta,
			final RequestMonitor rm) {
		// The following events can affect register values, refresh the state
		// of the expression.
		if (event instanceof IRegisterChangedDMEvent || event instanceof IMemoryChangedEvent
				|| (event instanceof PropertyChangeEvent && ((PropertyChangeEvent) event)
						.getProperty() == IDebugVMConstants.PROP_FORMATTED_VALUE_FORMAT_PREFERENCE)) {
			parentDelta.addNode(element, IModelDelta.STATE);
		} else if (event instanceof ElementFormatEvent) {
			int depth = ((ElementFormatEvent) event).getApplyDepth();
			if (depth != 0) {
				int deltaType = IModelDelta.CONTENT;
				if (depth == 1)
					deltaType = IModelDelta.STATE;

				Set<Object> elements = ((ElementFormatEvent) event).getElements();
				for (Object elem : elements) {
					parentDelta.addNode(elem, deltaType);
				}
			}
		}

		rm.done();
	}

	private final String MEMENTO_NAME = "BITFIELD_MEMENTO_NAME"; //$NON-NLS-1$

	@Override
	public void compareElements(IElementCompareRequest[] requests) {
		for (final IElementCompareRequest request : requests) {
			final String mementoName = request.getMemento().getString(MEMENTO_NAME);

			final IBitFieldDMContext regDmc = findDmcInPath(request.getViewerInput(), request.getElementPath(),
					IBitFieldDMContext.class);
			if (regDmc == null || mementoName == null) {
				request.done();
				continue;
			}

			//  Now go get the model data for the single register group found.
			try {
				getSession().getExecutor().execute(new DsfRunnable() {
					@Override
					public void run() {
						final IRegisters regService = getServicesTracker().getService(IRegisters.class);
						if (regService != null) {
							regService.getBitFieldData(regDmc,
									new DataRequestMonitor<IBitFieldDMData>(regService.getExecutor(), null) {
										@Override
										protected void handleCompleted() {
											if (getStatus().isOK()) {
												// Now make sure the register group is the one we want.
												request.setEqual(mementoName.equals("BitField." + getData().getName())); //$NON-NLS-1$
											}
											request.done();
										}
									});
						} else {
							request.done();
						}
					}
				});
			} catch (RejectedExecutionException e) {
				request.done();
			}
		}
	}

	@Override
	public void encodeElements(IElementMementoRequest[] requests) {
		for (final IElementMementoRequest request : requests) {
			final IBitFieldDMContext regDmc = findDmcInPath(request.getViewerInput(), request.getElementPath(),
					IBitFieldDMContext.class);
			if (regDmc == null) {
				request.done();
				continue;
			}

			//  Now go get the model data for the single register group found.
			try {
				getSession().getExecutor().execute(new DsfRunnable() {
					@Override
					public void run() {
						final IRegisters regService = getServicesTracker().getService(IRegisters.class);
						if (regService != null) {
							regService.getBitFieldData(regDmc,
									new DataRequestMonitor<IBitFieldDMData>(regService.getExecutor(), null) {
										@Override
										protected void handleCompleted() {
											if (getStatus().isOK()) {
												// Now make sure the register group is the one we want.
												request.getMemento().putString(MEMENTO_NAME,
														"BitField." + getData().getName()); //$NON-NLS-1$
											}
											request.done();
										}
									});
						} else {
							request.done();
						}
					}
				});
			} catch (RejectedExecutionException e) {
				request.done();
			}
		}
	}
}
