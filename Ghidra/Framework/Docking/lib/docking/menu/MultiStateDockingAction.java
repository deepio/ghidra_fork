/* ###
 * IP: GHIDRA
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
package docking.menu;

import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;

import javax.swing.Icon;
import javax.swing.JButton;

import docking.*;
import docking.action.*;
import docking.widgets.EventTrigger;
import ghidra.util.HelpLocation;
import ghidra.util.SystemUtilities;
import ghidra.util.exception.AssertException;
import resources.icons.EmptyIcon;

/**
 * An action that can be in one of multiple states.   The button of this action has a 
 * drop-down icon that allows users to change the state of the button.  Also, by default, as
 * the user presses the button, it will execute the action corresponding to the current 
 * state.
 *
 * @param <T> the type of the user data
 * @see MultiActionDockingAction
 */
public abstract class MultiStateDockingAction<T> extends DockingAction {

	private static Icon EMPTY_ICON = new EmptyIcon(16, 16);

	private List<ActionState<T>> actionStates = new ArrayList<>();
	private int currentStateIndex = 0;
	private MultiActionDockingActionIf multiActionGenerator;
	private MultipleActionDockingToolbarButton multipleButton;

	private boolean performActionOnPrimaryButtonClick = true;

	// A listener that will get called when the button (not the popup) is clicked.  Toolbar
	// actions do not use this listener. 
	private ActionListener clickListener = e -> {
		// stub for toolbar actions
	};

	public MultiStateDockingAction(String name, String owner) {
		this(name, owner, false);
	}

	protected MultiStateDockingAction(String name, String owner, boolean isToolbarAction) {
		super(name, owner);
		multiActionGenerator = context -> getStateActions();

		// set this here so we don't have to check for null elsewhere
		super.setToolBarData(new ToolBarData(null));

		if (!isToolbarAction) {
			// we need this listener to perform the action when the user click the button; 
			// toolbar actions have their own listener
			clickListener = e -> {
				actionPerformed(getActionContext());
			};
		}
	}

	public abstract void actionStateChanged(ActionState<T> newActionState, EventTrigger trigger);

	/**
	 * If <tt>doPerformAction</tt> is <tt>true</tt>, then, when the user clicks the
	 * button and not the drop-down arrow, the {@link #doActionPerformed(ActionContext)}
	 * method will be called.  If <tt>doPerformAction</tt> is <tt>false</tt>, then, when
	 * the user clicks the button and not the drop-down arrow, the popup menu will be shown, just
	 * as if the user had clicked the drop-down arrow.
	 * <p>
	 * Also, if the parameter is true, then the button will behave like a button in terms of
	 * mouse feedback.  If false, then the button will behave more like a label.
	 */
	public void setPerformActionOnPrimaryButtonClick(boolean doPerformAction) {
		performActionOnPrimaryButtonClick = doPerformAction;
		if (multipleButton == null) {
			return;
		}

		multipleButton.setPerformActionOnButtonClick(performActionOnPrimaryButtonClick);

		multipleButton.removeActionListener(clickListener);
		if (performActionOnPrimaryButtonClick) {
			multipleButton.addActionListener(clickListener);
		}
	}

	@Override
	public final void actionPerformed(ActionContext context) {
		if (!performActionOnPrimaryButtonClick) {
			SystemUtilities.runSwingLater(() -> multipleButton.showPopup(null));
			return;
		}

		doActionPerformed(context);
	}

	/**
	 * This is the callback to be overridden when the child wishes to respond to user button
	 * presses that are on the button and not the drop-down.  This will only be called if
	 * {@link #performActionOnPrimaryButtonClick} is true.
	 */
	protected void doActionPerformed(ActionContext context) {
		// override me to do work
	}

	private ActionContext getActionContext() {
		DockingWindowManager manager = DockingWindowManager.getActiveInstance();
		ComponentProvider provider = manager.getActiveComponentProvider();
		ActionContext localContext = provider == null ? null : provider.getActionContext(null);
		final ActionContext actionContext =
			localContext == null ? manager.getGlobalContext() : localContext;
		return actionContext;
	}

	protected List<DockingActionIf> getStateActions() {
		List<DockingActionIf> actions = new ArrayList<>(actionStates.size());
		for (ActionState<T> actionState : actionStates) {
			actions.add(new ActionStateAction(actionState));
		}
		return actions;
	}

	public void setGroup(String group) {
		ToolBarData tbd = getToolBarData();
		tbd.setToolBarGroup(group);
	}

	public void setSubGroup(String subGroup) {
		ToolBarData tbd = getToolBarData();
		tbd.setToolBarSubGroup(subGroup);
	}

	public void addActionState(ActionState<T> actionState) {
		actionStates.add(actionState);
		if (actionStates.size() == 1) {
			setCurrentActionState(actionState);
		}
	}

	public void setActionStates(List<ActionState<T>> newStates) {
		if (newStates.isEmpty()) {
			throw new IllegalArgumentException("You must provide at least one ActionState");
		}
		actionStates = new ArrayList<>(newStates);
		setCurrentActionState(actionStates.get(0));
	}

	public T getCurrentUserData() {
		return actionStates.get(currentStateIndex).getUserData();
	}

	public ActionState<T> getCurrentState() {
		return actionStates.get(currentStateIndex);
	}

	public List<ActionState<T>> getAllActionStates() {
		return new ArrayList<>(actionStates);
	}

	public void setCurrentActionStateByUserData(T t) {
		for (ActionState<T> actionState : actionStates) {
			if (actionState.getUserData() == t) {
				setCurrentActionState(actionState);
				return;
			}
		}

		throw new AssertException(
			"Attempted to set an action state by a user type not " + "contained herein: " + t);
	}

	public void setCurrentActionState(ActionState<T> actionState) {
		setCurrentActionStateWithTrigger(actionState, EventTrigger.API_CALL);
	}

	public void setCurrentActionStateWithTrigger(ActionState<T> actionState, EventTrigger trigger) {
		int indexOf = actionStates.indexOf(actionState);
		if (indexOf < 0) {
			throw new IllegalArgumentException(
				"Attempted to set actionState to unknown ActionState.");
		}
		currentStateIndex = indexOf;

		// we set the icon here to handle the odd case where this action is not used in a toolbar
		if (multipleButton != null) {
			setButtonState(actionState);
		}

		ToolBarData tbd = getToolBarData();
		tbd.setIcon(getIcon(actionState));

		setDescription(getTooTipText());
		actionStateChanged(actionState, trigger);
	}

	private Icon getIcon(ActionState<T> actionState) {
		Icon icon = actionState.getIcon();
		if (icon != null) {
			return icon;
		}
		return EMPTY_ICON;
	}

	@Override
	public JButton doCreateButton() {
		multipleButton = new MultipleActionDockingToolbarButton(multiActionGenerator);
		multipleButton.setPerformActionOnButtonClick(performActionOnPrimaryButtonClick);

		if (performActionOnPrimaryButtonClick) {
			multipleButton.addActionListener(clickListener);
		}
		else {
			multipleButton.removeActionListener(clickListener);
		}

		if (currentStateIndex >= 0) {
			ActionState<T> actionState = actionStates.get(currentStateIndex);
			setButtonState(actionState);
		}

		return multipleButton;
	}

	private void setButtonState(ActionState<T> actionState) {

		Icon icon = getIcon(actionState);
		multipleButton.setIcon(icon);
		multipleButton.setToolTipText(actionState.getName());
	}

	@Override
	public void setMenuBarData(MenuData newMenuData) {
		throw new UnsupportedOperationException();
	}

	protected void superSetMenuBarData(MenuData newMenuData) {
		super.setMenuBarData(newMenuData);
	}

	@Override
	public void setPopupMenuData(MenuData newMenuData) {
		throw new UnsupportedOperationException();
	}

	public String getTooTipText() {
		return getName() + ": " + getCurrentState().getName();
	}

//==================================================================================================
// Inner Classes
//==================================================================================================
	private class ActionStateAction extends DockingAction {

		private final ActionState<T> actionState;

		private ActionStateAction(ActionState<T> actionState) {
			super(actionState.getName(), "multiStateAction");
			this.actionState = actionState;
			setMenuBarData(
				new MenuData(new String[] { actionState.getName() }, actionState.getIcon()));
			HelpLocation helpLocation = actionState.getHelpLocation();
			if (helpLocation != null) {
				setHelpLocation(helpLocation);
			}
		}

		@Override
		public String getInceptionInformation() {
			// we want the debug info for these internal actions to be that of the outer class
			return MultiStateDockingAction.this.getInceptionInformation();
		}

		@Override
		public void actionPerformed(ActionContext context) {
			setCurrentActionStateWithTrigger(actionState, EventTrigger.GUI_ACTION);
		}

	}

}
