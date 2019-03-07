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
package docking;

import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.*;
import java.util.List;
import java.util.Map.Entry;

import javax.swing.*;

import org.jdom.Element;

import docking.action.DockingActionIf;
import docking.help.HelpService;
import generic.util.WindowUtilities;
import ghidra.framework.OperatingSystem;
import ghidra.framework.Platform;
import ghidra.framework.options.PreferenceState;
import ghidra.util.HelpLocation;
import ghidra.util.SystemUtilities;
import ghidra.util.datastruct.*;
import ghidra.util.exception.AssertException;
import ghidra.util.task.SwingUpdateManager;
import util.CollectionUtils;

/**
 * Manages the "Docking" arrangement of a set of components and actions. The components can be "docked" 
 * together or exist in their own window.  Actions can be associated with components so they
 * "move" with the component as it moved from one location to another.
 * 
 * Components are added via ComponentProviders.  A ComponentProvider is an interface for getting
 * a component and its related information.  The docking window manager will get the component
 * from the provider as needed.  It is up to the provider if it wants to reuse the component or
 * recreate a new one when the component is requested.  When the user "hides" (by using 
 * the x button on the component area) a component, the docking window manager removes all
 * knowledge of the component and will request it again from the provider if the component
 * becomes "unhidden".  The provider is also notified whenever a component is hidden.   Some
 * providers will use the notification to remove the provider from the docking window manager so
 * that the can not "unhide" the component using the built-in window menu.
 */
public class DockingWindowManager implements PropertyChangeListener, PlaceholderInstaller {

	final static String COMPONENT_MENU_NAME = "Window";

	private final static List<DockingActionIf> EMPTY_LIST = Collections.emptyList();

	private static DockingActionIf actionUnderMouse;
	private static Object objectUnderMouse;

	public static final String TOOL_PREFERENCES_XML_NAME = "PREFERENCES";
	public static final String DOCKING_WINDOWS_OWNER = "DockingWindows";

	/**
	 * The helpService field should be set to the appropriate help service provider.
	 */
	private static HelpService helpService = new DefaultHelpService();

	private static List<DockingWindowManager> instanceList = new ArrayList<>();

	private RootNode root;

	private PlaceholderManager placeholderManager;
	private LRUSet<ComponentPlaceholder> lastFocusedPlaceholders = new LRUSet<>(20);

	private ActivatedInfo activatedInfo = new ActivatedInfo();
	private ComponentPlaceholder focusedPlaceholder;
	private ComponentPlaceholder nextFocusedPlaceholder;
	private ComponentProvider defaultProvider;
	private static Component pendingRequestFocusComponent;

	private Map<String, ComponentProvider> providerNameCache = new HashMap<>();
	private Map<String, PreferenceState> preferenceStateMap = new HashMap<>();
	private DockWinListener docListener;
	private DockingActionManager actionManager;

	private WeakSet<DockingContextListener> contextListeners =
		WeakDataStructureFactory.createSingleThreadAccessWeakSet();

	// The update should happen fairly quickly, as it is what rebuilds the window layout
	private SwingUpdateManager rebuildUpdater = new SwingUpdateManager(100, 750, this::doUpdate);
	private boolean isVisible;
	private boolean isDocking;
	private boolean hasStatusBar;

	private EditWindow editWindow;
	private boolean windowsOnTop;

	private Window lastActiveWindow;

	/**
	 * Constructs a new DockingWindowManager
	 * @param toolName the name of the tool.
	 * @param images the images to use for windows in this window manager
	 * @param docListener the listener to be notified when the user closes the manager.
	 */
	public DockingWindowManager(String toolName, List<Image> images, DockWinListener docListener) {
		this(toolName, images, docListener, false, true, true, null);
	}

	/**
	 * Constructs a new DockingWindowManager
	 * 
	 * @param toolName the name of the tool
	 * @param images the list of icons to set on the window
	 * @param docListener the listener to be notified when the user closes the manager
	 * @param modal if true then the root window will be a modal dialog instead of a frame
	 * @param isDocking true for normal operation, false to suppress docking support(removes
	 * component headers and window menu)
	 * @param hasStatusBar if true a status bar will be created for the main window
	 * @param factory the drop target factory
	 */
	public DockingWindowManager(String toolName, List<Image> images, DockWinListener docListener,
			boolean modal, boolean isDocking, boolean hasStatusBar, DropTargetFactory factory) {

		KeyBindingOverrideKeyEventDispatcher.install();

		this.docListener = docListener;
		this.isDocking = isDocking;
		this.hasStatusBar = hasStatusBar;
		if (images == null) {
			images = new ArrayList<>();
		}

		root = new RootNode(this, toolName, images, modal, factory);
		actionManager = new DockingActionManager(this);

		KeyboardFocusManager km = KeyboardFocusManager.getCurrentKeyboardFocusManager();
		km.addPropertyChangeListener("permanentFocusOwner", this);

		addInstance(this);

		placeholderManager = new PlaceholderManager(this);
	}

	@Override
	public String toString() {
		return "DockingWindowManager: " + root.getTitle();
	}

	/**
	 * A static initializer allowing additional diagnostic actions
	 * to be enabled added to all frame and dialog windows.
	 * @param enable
	 */
	public static void enableDiagnosticActions(boolean enable) {
		DockingActionManager.enableDiagnosticActions(enable);
	}

	/**
	 * Sets the help service for the all docking window managers.
	 * @param helpSvc the help service to use.
	 */
	public static void setHelpService(HelpService helpSvc) {
		if (helpSvc == null) {
			throw new IllegalArgumentException("HelpService may not be null");
		}
		helpService = helpSvc;
	}

	/**
	 * Returns the global help service.
	 * @return the global help service.
	 */
	public static HelpService getHelpService() {
		return helpService;
	}

	List<DockingActionIf> getTemporaryPopupActions(ActionContext context) {
		return docListener.getPopupActions(context);
	}

	/**
	 * Returns the ComponentWindowingPlaceholder that currently has focus.
	 * @return the ComponentWindowingPlaceholder that currently has focus.
	 */
	public ComponentPlaceholder getFocusedProviderPlaceholder() {
		return focusedPlaceholder;
	}

	private static synchronized void addInstance(DockingWindowManager winMgr) {
		instanceList.add(winMgr);
	}

	private static synchronized void removeInstance(DockingWindowManager winMgr) {
		instanceList.remove(winMgr);
	}

	/**
	 * Get the docking window manager instance which corresponds to the specified window.
	 * @param win the window for which to find its parent docking window manager.
	 * @return docking window manager or null if unknown.
	 */
	private static DockingWindowManager getInstanceForWindow(Window win) {

		if (win == null) {
			return null;
		}

		Iterator<DockingWindowManager> iter = instanceList.iterator();
		while (iter.hasNext()) {
			DockingWindowManager winMgr = iter.next();
			if (winMgr.root.getFrame() == win) {
				return winMgr;
			}

			List<DetachedWindowNode> detachedWindows = winMgr.root.getDetachedWindows();
			List<DetachedWindowNode> safeAccessCopy = new LinkedList<>(detachedWindows);
			Iterator<DetachedWindowNode> windowIterator = safeAccessCopy.iterator();
			while (windowIterator.hasNext()) {
				DetachedWindowNode dw = windowIterator.next();
				if (dw.getWindow() == win) {
					return winMgr;
				}
			}
		}

		return null;
	}

	/**
	 * A convenience method for getting the window for <tt>component</tt> and then calling
	 * {@link #getInstanceForWindow(Window)}.
	 * @param component The component for which to get the associated {@link DockingWindowManager}
	 *        instance.
	 * @return The {@link DockingWindowManager} instance associated with <tt>component</tt>
	 */
	public static synchronized DockingWindowManager getInstance(Component component) {
		Window window = WindowUtilities.windowForComponent(component);
		while (window != null) {
			DockingWindowManager windowManager = getInstanceForWindow(window);
			if (windowManager != null) {
				return windowManager;
			}
			window = window.getOwner();
		}
		return null;

	}

	/**
	 * Returns the last active docking window manager which is visible.
	 * @return the last active docking window manager which is visible.
	 */
	public static synchronized DockingWindowManager getActiveInstance() {
		//
		// Assumption: the managers are put into the list in the order they are created.  The 
		//             most recently created manager is the last shown manager, making it the
		//             most active.  Any time we change the active manager, it will be placed
		//             in the back of the list.
		//
		for (int i = instanceList.size() - 1; i >= 0; i--) {
			DockingWindowManager mgr = instanceList.get(i);
			if (mgr.root.isVisible()) {
				return mgr;
			}
		}
		return null;
	}

	/**
	 * Returns a new list of all DockingWindowManager instances know to exist.
	 * @return a new list of all DockingWindowManager instances know to exist.
	 */
	public static synchronized List<DockingWindowManager> getAllDockingWindowManagers() {
		return new ArrayList<>(instanceList);
	}

	/**
	 * The specified docking window manager has just become active
	 * @param mgr the window manager that became active.
	 */
	static synchronized void setActiveManager(DockingWindowManager mgr) {
		if (instanceList.remove(mgr)) {
			instanceList.add(mgr);
		}
	}

	/**
	 * Register a specific Help content URL for a component.
	 * The DocWinListener will be notified with the helpURL if the specified
	 * component 'c' has focus and the help key is pressed. 
	 * @param c component on which to set help.
	 * @param helpLocation help content location
	 */
	public static void setHelpLocation(JComponent c, HelpLocation helpLocation) {
		DockingActionManager.setHelpLocation(c, helpLocation);
	}

	/**
	 * Set the tool name which is displayed as the title
	 * for all windows.
	 * @param toolName tool name / title
	 */
	public void setToolName(String toolName) {
		root.setToolName(toolName);
	}

	/**
	 * Set the Icon for all windows.
	 * @param icon image icon
	 */
	public void setIcon(ImageIcon icon) {
		root.setIcon(icon);
	}

	/**
	 * Returns any action that is bound to the given keystroke for the tool associated with this
	 * DockingWindowManager instance.
	 * @param keyStroke The keystroke to check for key bindings.
	 * @return The action that is bound to the keystroke, or null of there is no binding for the
	 *         given keystroke.
	 */
	Action getActionForKeyStroke(KeyStroke keyStroke) {
		return actionManager.getDockingKeyAction(keyStroke);
	}

	/**
	 * Returns true if this manager contains the given provider.
	 * 
	 * @param provider the provider for which to check
	 * @return true if this manager contains the given provider.
	 */
	public boolean containsProvider(ComponentProvider provider) {
		return placeholderManager.containsProvider(provider);
	}

	PlaceholderManager getPlaceholderManager() {
		return placeholderManager;
	}

	DockingActionManager getActionManager() {
		return actionManager;
	}

	RootNode getRootNode() {
		return root;
	}

	/**
	 * Returns the root window frame.
	 * @return the root window frame.
	 */
	public JFrame getRootFrame() {
		if (root == null) {
			return null; // must have been disposed
		}
		return root.getFrame();
	}

	/**
	 * Sets the provider that should get the default focus when no component has focus.
	 * @param provider the provider that should get the default focus when no component has focus.
	 */
	public void setDefaultComponent(ComponentProvider provider) {
		defaultProvider = provider;
	}

	public ActionContext getGlobalContext() {
		if (defaultProvider != null) {
			ActionContext actionContext = defaultProvider.getActionContext(null);
			if (actionContext != null) {
				return actionContext;
			}
		}
		return new ActionContext();
	}

	/**
	 * Get the window which contains the specified Provider's component.
	 * @param provider component provider
	 * @return window or null if component is not visible or not found.
	 */
	public Window getProviderWindow(ComponentProvider provider) {
		ComponentPlaceholder placeholder = getActivePlaceholder(provider);
		if (placeholder != null) {
			return root.getWindow(placeholder);
		}
		return null;
	}

	ComponentPlaceholder getActivePlaceholder(ComponentProvider provider) {
		return placeholderManager.getActivePlaceholder(provider);
	}

	/**
	 * Returns the active window (or the root window if nobody has yet been made active).
	 * @return the active window.
	 */
	public Window getActiveWindow() {
		if (lastActiveWindow != null) {
			return lastActiveWindow;
		}

		return root.getFrame();
	}

	/**
	 * Returns the current active component.
	 * @return the current active component.
	 */
	public Component getActiveComponent() {
		if (focusedPlaceholder != null) {
			return focusedPlaceholder.getComponent();
		}
		return null;
	}

	/**
	 * Returns the component which has focus
	 * @return the placeholder
	 */
	public ComponentPlaceholder getFocusedComponent() {
		return focusedPlaceholder;
	}

	private ComponentPlaceholder getDefaultFocusComponent() {
		return placeholderManager.getActivePlaceholder(defaultProvider);
	}

	public boolean isActiveProvider(ComponentProvider provider) {
		boolean isActiveWindowManager = (this == getActiveInstance());
		boolean isFocusedProvider =
			(focusedPlaceholder != null) && (focusedPlaceholder.getProvider() == provider);
		return isActiveWindowManager && isFocusedProvider;
	}

	/**
	 * Sets the visible state of the set of docking windows.
	 * @param state if true the main window and all sub-windows are set to be visible.  If
	 * state is false, then all windows are set to be invisible.
	 */
	public synchronized void setVisible(boolean state) {
		if (state != isVisible) {
			isVisible = state;
			if (state) {
				scheduleUpdate();
			}
			root.setVisible(state);
		}
	}

	/**
	 * Returns true if the set of windows associated with this window manager are visible.
	 * @return true if the set of windows associated with this window manager are visible.
	 */
	public boolean isVisible() {
		return isVisible;
	}

	/**
	 * Returns true if the specified provider's component is visible
	 * @param provider component provider
	 * @return true if the specified provider's component is visible
	 */
	public boolean isVisible(ComponentProvider provider) {
		ComponentPlaceholder placeholder = getActivePlaceholder(provider);
		if (placeholder != null) {
			return placeholder.isShowing();
		}
		return false;
	}

	/**
	 * Adds a new component (via the provider) to be managed by this docking window manager.
	 * The component is initially hidden.
	 * @param provider the component provider
	 */
	public void addComponent(ComponentProvider provider) {
		addComponent(provider, true);
	}

	/**
	 * Adds a new component (vial the provider) to be managed by this docking window manager.
	 * The component will be initially shown or hidden based on the the "show" parameter.
	 * @param provider the component provider.
	 * @param show indicates whether or not the component should be initially shown.
	 */
	public void addComponent(ComponentProvider provider, boolean show) {

		checkIfAlreadyAdded(provider);
		HelpLocation helpLoc = provider.getHelpLocation();
		registerHelpLocation(provider, helpLoc);
		ComponentPlaceholder placeholder = placeholderManager.createOrRecyclePlaceholder(provider);
		showComponent(placeholder, show, true);
		scheduleUpdate();
	}

	private void registerHelpLocation(ComponentProvider provider, HelpLocation helpLocation) {
		HelpLocation registeredHelpLocation = helpService.getHelpLocation(provider);
		if (registeredHelpLocation != null) {
			return; // nothing to do; location already registered
		}

		if (helpLocation == null) {
			helpLocation = new HelpLocation(provider.getOwner(), provider.getName());
		}

		helpService.registerHelp(provider, helpLocation);
	}

	private void checkIfAlreadyAdded(ComponentProvider provider) {
		if (containsProvider(provider)) {
			throw new AssertException(
				"ComponentProvider " + provider.getName() + " was already added.");
		}
	}

	/**
	 * Returns the ComponentProvider with the given name.  If more than one provider exists with the name,
	 * one will be returned, but it could be any one of them.
	 * @param name the name of the provider to return.
	 * @return a provider with the given name, or null if no providers with that name exist.
	 */
	public ComponentProvider getComponentProvider(String name) {
		ComponentProvider cachedProvider = providerNameCache.get(name);
		if (cachedProvider != null) {
			if (containsProvider(cachedProvider)) {
				return cachedProvider;
			}
			providerNameCache.remove(name);
		}

		Set<ComponentProvider> providers = placeholderManager.getActiveProviders();
		for (ComponentProvider provider : providers) {
			if (name.equals(provider.getName())) {
				providerNameCache.put(name, provider);
				return provider;
			}
		}
		return null;
	}

	/**
	 * The <b>first</b> provider instance with a class equal to that of the given class
	 * 
	 * @param clazz the class of the desired provider
	 * @return the <b>first</b> provider instance with a class equal to that of the given class.
	 * @see #getComponentProviders(Class)
	 */
	public <T extends ComponentProvider> T getComponentProvider(Class<T> clazz) {
		List<T> allProviders = getComponentProviders(clazz);
		return CollectionUtils.any(allProviders);
	}

	/**
	 * Gets all components providers with a matching class.  Some component providers will have
	 * multiple instances in the tool
	 * 
	 * @param clazz The class of the provider
	 * @return all found provider instances
	 */
	public <T extends ComponentProvider> List<T> getComponentProviders(Class<T> clazz) {
		List<T> list = new ArrayList<>();
		Set<ComponentProvider> providers = placeholderManager.getActiveProviders();
		for (ComponentProvider provider : providers) {
			if (clazz.isAssignableFrom(provider.getClass())) {
				list.add(clazz.cast(provider));
			}
		}
		return list;
	}

	DockableComponent getDockableComponent(ComponentProvider provider) {
		ComponentPlaceholder placeholder = placeholderManager.getPlaceholder(provider);
		return placeholder.getComponent();
	}

	ComponentPlaceholder getPlaceholder(ComponentProvider provider) {
		return placeholderManager.getPlaceholder(provider);
	}

	/**
	 * Set whether a component's header should be shown; the header is the
	 * component that is dragged in order to move the component within the
	 * tool, or out of the tool into a separate window.
	 * @param provider provider of the visible component in the tool
	 * @param b true means to show the header
	 */
	public void showComponentHeader(ComponentProvider provider, boolean b) {
		ComponentPlaceholder placeholder = getActivePlaceholder(provider);
		if (placeholder != null) {
			placeholder.showHeader(b);
			scheduleUpdate();
		}
	}

	public void setIcon(ComponentProvider provider, Icon icon) {
		ComponentPlaceholder placeholder = getActivePlaceholder(provider);
		placeholder.setIcon(icon);
		scheduleUpdate();
	}

	public void updateTitle(ComponentProvider provider) {

		String title = provider.getTitle();
		if (title == null) {
			title = "";
		}

		ComponentPlaceholder placeholder = getActivePlaceholder(provider);
		if (placeholder == null) {
			return; // shouldn't happen
		}

		placeholder.update();
		scheduleUpdate();

		DetachedWindowNode wNode = placeholder.getWindowNode();
		if (wNode != null) {
			wNode.updateTitle();
		}
	}

	/**
	 * Returns the current subtitle for the component for the given provider.
	 * @param provider the component provider of the component for which to get its subtitle.
	 * @return the current subtitle for the component for the given provider.
	 */
	public String getSubTitle(ComponentProvider provider) {
		ComponentPlaceholder placeholder = getActivePlaceholder(provider);
		if (placeholder != null) {
			return placeholder.getSubTitle();
		}
		return "";
	}

	/**
	 * Removes the ComponentProvider (component) from the docking windows manager.  The location
	 * of the window will be remember and reused if the provider is added back in later.
	 * @param provider the provider to be removed.
	 */
	public void removeComponent(ComponentProvider provider) {
		placeholderManager.removeComponent(provider);
	}

	/**
	 * Get an iterator over the actions for the given provider.
	 * @param provider the component provider for which to iterate over all its owned actions.
	 * @return null if the provider does not exist in this window manager
	 */
	public Iterator<DockingActionIf> getComponentActions(ComponentProvider provider) {
		ComponentPlaceholder placeholder = getActivePlaceholder(provider);
		if (placeholder != null) {
			return placeholder.getActions();
		}
		return EMPTY_LIST.iterator();
	}

	/**
	 * Removes all components and actions associated with the given owner. 
	 * @param owner the name of the owner whose associated component and actions should be removed.
	 */
	public void removeAll(String owner) {
		actionManager.removeAll(owner);
		placeholderManager.removeAll(owner);
		scheduleUpdate();
	}

	/**
	 * Adds an action that will be associated with the given provider.  These actions will
	 * appear in the local header for the component as a toolbar button or a drop-down menu
	 * item if it has an icon and menu path respectively.
	 * @param provider the provider whose header on which the action is to be placed.
	 * @param action the action to add to the providers header bar.
	 */
	public void addLocalAction(ComponentProvider provider, DockingActionIf action) {
		ComponentPlaceholder placeholder = getActivePlaceholder(provider);
		if (placeholder == null) {
			throw new IllegalArgumentException("Unknown component provider: " + provider);
		}
		placeholder.addAction(action);
		actionManager.addLocalAction(action, provider);
	}

	/**
	 * Removes the action from the given provider's header bar.
	 * @param provider the provider whose header bar from which the action should be removed.
	 * @param action the action to be removed from the provider's header bar.
	 */
	public void removeProviderAction(ComponentProvider provider, DockingActionIf action) {
		ComponentPlaceholder placeholder = getActivePlaceholder(provider);
		if (placeholder != null) {
			actionManager.removeLocalAction(action);
			placeholder.removeAction(action);
		}
	}

	/**
	 * Adds an action to the global menu or toolbar which appear in the main frame. If
	 * the action has a menu path, it will be in the menu.  If it has an icon, it will
	 * appear in the toolbar.
	 * @param action the action to be added.
	 */
	public void addToolAction(DockingActionIf action) {
		actionManager.addToolAction(action);
		scheduleUpdate();
	}

	/**
	 * Removes the given action from the global menu and toolbar.
	 * @param action the action to be removed.
	 */
	public void removeToolAction(DockingActionIf action) {
		actionManager.removeToolAction(action);
		scheduleUpdate();
	}

	public Collection<DockingActionIf> getActions(String fullActionName) {
		return actionManager.getAllDockingActionsByFullActionName(fullActionName);
	}

	/**
	 * Hides or shows the component associated with the given provider.
	 * <p><br>
	 * <b>Note: </b> This method will not show the given provider if it has not previously been
	 * added via <tt>addComponent(...)</tt>.
	 * 
	 * @param provider the provider of the component to be hidden or shown.
	 * @param visibleState true to show the component, false to hide it.
	 * @see #addComponent(ComponentProvider)
	 * @see #addComponent(ComponentProvider, boolean)
	 */
	public void showComponent(ComponentProvider provider, boolean visibleState) {
		ComponentPlaceholder placeholder = getActivePlaceholder(provider);
		if (placeholder != null) {
			showComponent(placeholder, visibleState, true);
		}
	}

	public void toFront(ComponentProvider provider) {
		ComponentPlaceholder placeholder = getActivePlaceholder(provider);
		if (placeholder == null) {
			return;
		}

		if (!placeholder.isShowing()) {
			showComponent(placeholder, true, false);
		}

		movePlaceholderToFront(placeholder, false);
	}

	public void toFront(final Window window) {
		if (window == null) {
			return;
		}

		if (window == getMainWindow()) {
			// we don't want special handling for the tool frame, as it triggers flashing
			window.toFront();
			return;
		}

		OperatingSystem operatingSystem = Platform.CURRENT_PLATFORM.getOperatingSystem();
		if (operatingSystem == OperatingSystem.WINDOWS) {
			//
			// Handle the window being minimized (Windows doesn't always raise the window when
			// calling setVisible()
			// 
			if (window instanceof Frame) {
				Frame frame = (Frame) window;
				int state = frame.getState();
				if ((state & Frame.ICONIFIED) == Frame.ICONIFIED) {
					frame.setState(Frame.NORMAL);
					return; // this is enough to bring the window to the front on Windows
				}
			}

			window.setVisible(false);
			window.setVisible(true);
		}
		else if (operatingSystem == OperatingSystem.LINUX) {
			//
			// Handle the window being minimized (Linux doesn't always raise the window when
			// calling setVisible()
			// 
			if (window instanceof Frame) {
				Frame frame = (Frame) window;
				int state = frame.getState();
				if ((state & Frame.ICONIFIED) == Frame.ICONIFIED) {
					frame.setState(Frame.NORMAL);
				}
			}

			window.toFront();
		}
		else {
			// mac - it actually works on the mac
			window.toFront();
		}
	}

	/**
	 * Releases all resources used by this docking window manager.  Once the dispose method
	 * is called, no other calls to this object should be made.
	 */
	public synchronized void dispose() {
		if (root == null) {
			return;
		}

		rebuildUpdater.dispose();

		KeyboardFocusManager mgr = KeyboardFocusManager.getCurrentKeyboardFocusManager();
		mgr.removePropertyChangeListener("permanentFocusOwner", this);

		actionManager.dispose();
		root.dispose();

		placeholderManager.disposePlaceholders();

		setNextFocusPlaceholder(null);
		removeInstance(this);
		root = null;
	}

	/**
	 * Shows or hides the component associated with the given placeholder object. 
	 * 
	 * @param placeholder the component placeholder object for the component to be shown or hidden.
	 * @param visibleState true to show or false to hide.
	 * @param requestFocus True signals that the system should request focus on the component.
	 */
	void showComponent(final ComponentPlaceholder placeholder, final boolean visibleState,
			boolean requestFocus) {
		if (root == null) {
			return;
		}

		if (visibleState == placeholder.isShowing()) {
			if (visibleState) {
				movePlaceholderToFront(placeholder, true);
				setNextFocusPlaceholder(placeholder);
				scheduleUpdate();
			}
			return;
		}

		placeholder.show(visibleState);
		movePlaceholderToFront(placeholder, false);

		if (visibleState) {
			if (placeholder.getNode() == null) {
				root.add(placeholder);
			}
			if (requestFocus) {
				setNextFocusPlaceholder(placeholder);
			}
		}
		else {
			if (focusedPlaceholder == placeholder) {
				clearFocusedComponent();
			}
		}

		scheduleUpdate();
	}

	private void movePlaceholderToFront(ComponentPlaceholder placeholder, boolean emphasisze) {
		placeholder.toFront();

		if (emphasisze) {
			activatedInfo.activated(placeholder);
		}

		toFront(root.getWindow(placeholder));
	}

	/**
	 * Generates a JDOM element object for saving the window managers state to XML.
	 * @param rootXMLElement The root element to which to save XML data.
	 */
	public void saveToXML(Element rootXMLElement) {
		Element rootNodeElement = saveWindowingDataToXml();
		if (focusedPlaceholder != null) {
			rootNodeElement.setAttribute("FOCUSED_OWNER", focusedPlaceholder.getOwner());
			rootNodeElement.setAttribute("FOCUSED_NAME", focusedPlaceholder.getName());
			rootNodeElement.setAttribute("FOCUSED_TITLE", focusedPlaceholder.getTitle());
		}

		rootXMLElement.removeChild(rootNodeElement.getName());
		rootXMLElement.addContent(rootNodeElement);

		Element preferencesElement = savePreferencesToXML();
		rootXMLElement.removeChild(preferencesElement.getName());
		rootXMLElement.addContent(preferencesElement);
	}

	/**
	 * Save this docking window manager's window layout and positioning information as XML.
	 * @return An XML element with the above information.
	 */
	public Element saveWindowingDataToXml() {
		return root.saveToXML();
	}

	/**
	 * Restores the docking window managers state from the XML information.
	 * @param rootXMLElement JDOM element from which to extract the state information.
	 */
	public void restoreFromXML(Element rootXMLElement) {
		Element rootNodeElement = rootXMLElement.getChild(RootNode.ROOT_NODE_ELEMENT_NAME);
		restoreWindowDataFromXml(rootNodeElement);
		// load the tool preferences
		restorePreferencesFromXML(rootXMLElement);
	}

	/**
	 * Restore to the docking window manager the layout and positioning information from XML.
	 * @param windowData The XML element containing the above information.
	 */
	public void restoreWindowDataFromXml(Element windowData) {
		//
		// Clear our focus history, as we are changing placeholders' providers, so the old focus
		// is no longer relevant.
		// 
		clearFocusedComponent();
		lastFocusedPlaceholders.clear();

		// 
		// Save off the active  providers.  They will be re-assigned to new placeholders.
		//
		Map<ComponentProvider, ComponentPlaceholder> activeProviders =
			placeholderManager.getActiveProvidersToPlaceholders();

		//
		// Load the placeholders 
		// 
		List<ComponentPlaceholder> restoredPlaceholders = root.restoreFromXML(windowData);
		placeholderManager = new PlaceholderManager(this, restoredPlaceholders);

		String focusedOwner = windowData.getAttributeValue("FOCUSED_OWNER");
		String focusedName = windowData.getAttributeValue("FOCUSED_NAME");
		String focusedTitle = windowData.getAttributeValue("FOCUSED_TITLE");
		ComponentPlaceholder lastFoundFocusReplacement = null;

		List<Entry<ComponentProvider, ComponentPlaceholder>> sortedProviders =
			sortActiveProviders(activeProviders);
		for (Entry<ComponentProvider, ComponentPlaceholder> entry : sortedProviders) {
			ComponentProvider provider = entry.getKey();
			ComponentPlaceholder oldPlaceholder = entry.getValue();
			ComponentPlaceholder newPlaceholder =
				placeholderManager.replacePlaceholder(provider, oldPlaceholder);

			// Odd case: the replacement placeholder is reused and it's title is different
			// 			 than the outgoing placeholder.  In that case, use the new placeholder
			//           as a reasonable default component to focus.
			if (SystemUtilities.isEqual(focusedTitle, oldPlaceholder.getTitle())) {
				lastFoundFocusReplacement = newPlaceholder;
			}
		}

		restoreSavedFocusedPlaceholder(focusedOwner, focusedName, focusedTitle,
			lastFoundFocusReplacement);

		placeholderManager.resetPlaceholdersWithoutProviders();

		scheduleUpdate();
	}

	private void restoreSavedFocusedPlaceholder(String focusOwner, String focusName,
			String focusTitle, ComponentPlaceholder bestFocusReplacementPlaceholder) {

		if (bestFocusReplacementPlaceholder != null) {
			// we've found already a preferred replacement
			setNextFocusPlaceholder(bestFocusReplacementPlaceholder);
			return;
		}

		restoreFocusOwner(focusOwner, focusName);
	}

	/** 
	 * Sorts the active providers by window group.  This ensures that the dependent window groups
	 * are loaded after their dependencies have been.   
	 */
	private List<Entry<ComponentProvider, ComponentPlaceholder>> sortActiveProviders(
			Map<ComponentProvider, ComponentPlaceholder> activeProviders) {

		Set<Entry<ComponentProvider, ComponentPlaceholder>> entrySet = activeProviders.entrySet();

		List<Entry<ComponentProvider, ComponentPlaceholder>> list = new ArrayList<>(entrySet);
		Collections.sort(list, (e1, e2) -> {

			ComponentProvider p1 = e1.getKey();
			ComponentProvider p2 = e2.getKey();
			String g1 = p1.getWindowGroup();
			String g2 = p2.getWindowGroup();
			return g1.compareToIgnoreCase(g2);
		});
		return list;
	}

	@Override
	public void installPlaceholder(ComponentPlaceholder placeholder, WindowPosition position) {
		root.add(placeholder, position);
	}

	@Override
	public void uninstallPlaceholder(ComponentPlaceholder placeholder, boolean keepAround) {
		disposePlaceholder(placeholder, keepAround);
		clearCurrentOrPendingFocusForRemovedPlaceholder(placeholder);
	}

	private void disposePlaceholder(ComponentPlaceholder placeholder, boolean keepAround) {
		Iterator<DockingActionIf> iter = placeholder.getActions();
		while (iter.hasNext()) {
			DockingActionIf action = iter.next();
			actionManager.removeLocalAction(action);
		}

		ComponentNode node = placeholder.getNode();
		if (node == null) {
			return;
		}
		node.remove(placeholder, keepAround);
	}

	synchronized void clearCurrentOrPendingFocusForRemovedPlaceholder(
			ComponentPlaceholder placeholder) {

		if (focusedPlaceholder == placeholder) {
			clearFocusedComponent();
		}
		else if (nextFocusedPlaceholder == placeholder) {
			clearFocusedComponent();
		}
	}

	/**
	 * Moves the component associated with the given source placeholder object from its current
	 * docked location to its own window that will be anchored at the given point.
	 * @param source the component placeholder containing the component to be moved.
	 * @param p the location at which to create a new window for the component.
	 */
	void movePlaceholder(ComponentPlaceholder source, Point p) {
		ComponentNode sourceNode = source.getNode();
		sourceNode.remove(source);
		root.add(source, p);
		scheduleUpdate();
	}

	/**
	 * Moves the component associated with the given source placeholder object to a new docked 
	 * location relative to the given destination placeholder object
	 * 
	 * @param source the component placeholder for the component being moved
	 * @param destination the component placeholder object used to base to move
	 * @param windowPosition a code specifying the docking relationship between two placeholders
	 */
	void movePlaceholder(ComponentPlaceholder source, ComponentPlaceholder destination,
			WindowPosition windowPosition) {
		ComponentNode sourceNode = source.getNode();
		if (destination != null) {
			ComponentNode destinationNode = destination.getNode();
			sourceNode.remove(source);
			if (windowPosition == WindowPosition.STACK) {
				destinationNode.add(source);
			}
			else {
				destinationNode.split(source, windowPosition);
			}
		}
		else {
			sourceNode.remove(source);
			root.add(source, WindowPosition.RIGHT);
		}

		setNextFocusPlaceholder(source);
		scheduleUpdate();
	}

	/**
	 * Notifies the docking windows listener that the close button has been pressed on
	 * the main window frame.
	 */
	void close() {
		docListener.close();
	}

	boolean isDocking() {
		return isDocking;
	}

	/**
	 * Builds the window menu containing a menu item for each component.
	 */
	private void buildComponentMenu() {

		if (!isDocking || !isVisible) {
			return;
		}

		if (isWindowMenuShowing()) {
			// Stop menu items from being disposed; this causes exceptions when they are pressed
			scheduleUpdate();
			return;
		}

		actionManager.removeAll(DOCKING_WINDOWS_OWNER);

		Map<String, List<ComponentPlaceholder>> permanentMap = new HashMap<>();
		Map<String, List<ComponentPlaceholder>> transientMap = new HashMap<>();

		Map<ComponentProvider, ComponentPlaceholder> map =
			placeholderManager.getActiveProvidersToPlaceholders();
		Set<Entry<ComponentProvider, ComponentPlaceholder>> entrySet = map.entrySet();
		for (Entry<ComponentProvider, ComponentPlaceholder> entry : entrySet) {
			ComponentProvider provider = entry.getKey();
			ComponentPlaceholder placeholder = entry.getValue();

			String subMenuName = provider.getWindowSubMenuName();
			if (provider.isTransient()) {
				addToMap(transientMap, subMenuName, placeholder);
			}
			else {
				addToMap(permanentMap, subMenuName, placeholder);
			}
		}
		promoteSingleMenuGroups(permanentMap);
		promoteSingleMenuGroups(transientMap);

		createActions(transientMap, true);
		createActions(permanentMap, false);
		createWindowActions();

		actionManager.update();
	}

	private boolean isWindowMenuShowing() {
		MenuElement[] selectedPath = MenuSelectionManager.defaultManager().getSelectedPath();
		if (selectedPath == null || selectedPath.length == 0) {
			return false;
		}

		JMenu menu = getMenuForSelection(selectedPath);
		if (menu == null) {
			return false;
		}

		String text = menu.getText();
		return text.equals(COMPONENT_MENU_NAME);
	}

	private JMenu getMenuForSelection(MenuElement[] selectedPath) {
		for (MenuElement element : selectedPath) {
			if (element instanceof JMenu) {
				return (JMenu) element;
			}
		}
		return null;
	}

	private void createActions(Map<String, List<ComponentPlaceholder>> map, boolean isTransient) {
		List<ShowComponentAction> actionList = new ArrayList<>();
		for (String subMenuName : map.keySet()) {
			List<ComponentPlaceholder> placeholders = map.get(subMenuName);
			for (ComponentPlaceholder placeholder : placeholders) {
				actionList.add(
					new ShowComponentAction(this, placeholder, subMenuName, isTransient));
			}
			if (subMenuName != null) {
				// add an 'add all' action for the sub-menu
				actionList.add(new ShowAllComponentsAction(this, placeholders, subMenuName));
			}
		}
		Collections.sort(actionList);
		for (ShowComponentAction action : actionList) {
			actionManager.addToolAction(action);
		}
	}

	private void promoteSingleMenuGroups(Map<String, List<ComponentPlaceholder>> map) {
		List<String> lists = new ArrayList<>(map.keySet());
		for (String key : lists) {
			List<ComponentPlaceholder> list = map.get(key);
			if (key != null && list.size() == 1) {
				addToMap(map, null, list.get(0));
				map.remove(key);
			}
		}
	}

	private void addToMap(Map<String, List<ComponentPlaceholder>> map, String menuGroup,
			ComponentPlaceholder placeholder) {

		List<ComponentPlaceholder> list = map.get(menuGroup);
		if (list == null) {
			list = new ArrayList<>();
			map.put(menuGroup, list);
		}
		list.add(placeholder);
	}

	private void createWindowActions() {
		List<DetachedWindowNode> windows = root.getDetachedWindows();
		List<ShowWindowAction> actions = new ArrayList<>();
		for (DetachedWindowNode node : windows) {
			Window window = node.getWindow();
			if (window != null) {
				actions.add(new ShowWindowAction(node));
			}
		}

		Collections.sort(actions);
		for (ShowWindowAction action : actions) {
			actionManager.addToolAction(action);
		}
	}

	/*
	 * Notifies the window manager that an update is needed
	 */
	void scheduleUpdate() {
		rebuildUpdater.updateLater();
	}

	private boolean updatePending() {
		return rebuildUpdater.isBusy();
	}

	/*
	 * Updates the component tree as needed
	 */
	private synchronized void doUpdate() {

		if (!isVisible) {
			return;
		}

		root.update(); // do this before rebuilding the menu, as new windows may be opened

		buildComponentMenu();
		SystemUtilities.runSwingLater(() -> updateFocus());
	}

	private void updateFocus() {
		if (updatePending()) {
			// we will get called again
			return;
		}

		if (root == null) {
			// This method is called from invokeLater(); we may have been disposed since then
			return;
		}

		// still loading components, so come back later when we can request focus
		if (!getMainWindow().isShowing()) {
			scheduleUpdate();
			return;
		}

		updateFocus(maybeGetPlaceholderToFocus());
	}

	private synchronized void setNextFocusPlaceholder(ComponentPlaceholder placeholder) {
		nextFocusedPlaceholder = placeholder;
	}

	private synchronized ComponentPlaceholder maybeGetPlaceholderToFocus() {
		if (nextFocusedPlaceholder != null) {
			ComponentPlaceholder temp = nextFocusedPlaceholder;
			setNextFocusPlaceholder(null);
			return temp;
		}

		KeyboardFocusManager kfm = KeyboardFocusManager.getCurrentKeyboardFocusManager();
		Component focusOwner = kfm.getFocusOwner();
		if (focusOwner == null) {
			return findNextFocusedComponent();
		}
		return null;
	}

	private void updateFocus(final ComponentPlaceholder placeholder) {
		if (placeholder == null) {
			return;
		}

		SystemUtilities.runSwingLater(() -> {
			KeyboardFocusManager kfm = KeyboardFocusManager.getCurrentKeyboardFocusManager();
			Window activeWindow = kfm.getActiveWindow();
			if (activeWindow == null) {
				// our application isn't focused--don't do anything
				return;
			}

			placeholder.requestFocus();
		});
	}

	/**
	 * Display an text edit box on top of the specified component.
	 * @param defaultText initial text to be displayed in edit box
	 * @param c component over which the edit box will be placed
	 * @param r specifies the bounds of the edit box relative to the 
	 * component.  The height is ignored.  The default text field height 
	 * is used as the preferred height. 
	 * @param listener when the edit is complete, this listener is notified 
	 * with the new text.  The edit box is dismissed prior to notifying
	 * the listener.
	 */
	public void showEditWindow(String defaultText, Component c, Rectangle r,
			EditListener listener) {
		if (editWindow == null) {
			editWindow = new EditWindow(this);
		}
		editWindow.show(defaultText, c, r, listener);
	}

	void restoreFocusOwner(String focusOwner, String focusName) {
		if (focusOwner == null) {
			// nothing to restore
			setNextFocusPlaceholder(getDefaultFocusComponent());
			return;
		}

		ComponentPlaceholder focusReplacement = getDefaultFocusComponent();
		Map<ComponentProvider, ComponentPlaceholder> map =
			placeholderManager.getActiveProvidersToPlaceholders();
		Set<Entry<ComponentProvider, ComponentPlaceholder>> entrySet = map.entrySet();
		for (Entry<ComponentProvider, ComponentPlaceholder> entry : entrySet) {
			ComponentProvider provider = entry.getKey();
			ComponentPlaceholder placeholder = entry.getValue();
			if (provider.getOwner().equals(focusOwner) && provider.getName().equals(focusName)) {
				focusReplacement = placeholder;
				break; // found one!
			}
		}

		setNextFocusPlaceholder(focusReplacement);
	}

	private void setFocusedComponent(ComponentPlaceholder placeholder) {

		if (focusedPlaceholder != null) {
			if (focusedPlaceholder == placeholder) {
				return; // ignore if we are already focused
			}

			focusedPlaceholder.setSelected(false);
		}

		focusedPlaceholder = placeholder;

		// put the last focused placeholder at the front of the list for restoring focus work later
		lastFocusedPlaceholders.add(focusedPlaceholder);

		focusedPlaceholder.setSelected(true);
		WindowNode topLevelNode = focusedPlaceholder.getTopLevelNode();
		if (topLevelNode == null) {
			return;
		}
		topLevelNode.setLastFocusedProviderInWindow(focusedPlaceholder);
		root.notifyWindowFocusChanged(topLevelNode);
	}

	private ComponentPlaceholder findNextFocusedComponent() {
		Iterator<ComponentPlaceholder> iterator = lastFocusedPlaceholders.iterator();
		while (iterator.hasNext()) {
			ComponentPlaceholder placeholder = iterator.next();
			if (placeholder.isShowing()) {
				return placeholder;
			}
			iterator.remove();
		}

		return getActivePlaceholder(defaultProvider);
	}

	private void clearFocusedComponent() {
		if (focusedPlaceholder != null) {
			lastFocusedPlaceholders.remove(focusedPlaceholder);
			focusedPlaceholder.setSelected(false);
			WindowNode topLevelNode = focusedPlaceholder.getTopLevelNode();
			if (topLevelNode != null) {
				topLevelNode.setLastFocusedProviderInWindow(null);
				root.notifyWindowFocusChanged(topLevelNode);
			}
		}

		focusedPlaceholder = null;
		setNextFocusPlaceholder(null);
	}

	/**
	 * Invoked by associated docking windows when they become active or inactive
	 * 
	 * @param window the active window 
	 * @param active true signals that this DockingWindowManager has become active
	 */
	void setActive(Window window, boolean active) {
		if (root == null) {
			return;
		}
		actionManager.setActive(active);
		if (active) {
			setActiveManager(this);
			if (focusedPlaceholder != null && root.getWindow(focusedPlaceholder) == window) {
				focusedPlaceholder.setSelected(true);
			}
		}
		else if (focusedPlaceholder != null) {
			focusedPlaceholder.setSelected(false);
		}
	}

	static void requestFocus(Component component) {

		if (component.hasFocus()) {
			return;
		}
		if (pendingRequestFocusComponent != null) {
			pendingRequestFocusComponent = null; // only do it once so that we don't get stuck in this state
			return;
		}
		pendingRequestFocusComponent = component;
		pendingRequestFocusComponent.requestFocus();
	}

	@Override
	public void propertyChange(PropertyChangeEvent evt) {

		Window win = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
		if (!isMyWindow(win)) {
			return;
		}

		lastActiveWindow = win;

		// adjust the focus if no component within the window has focus
		Component newFocusComponent = (Component) evt.getNewValue();
		if (newFocusComponent == null) {
			return; // we'll get called again with the correct value
		}

		DockableComponent dockableComponent =
			getDockableComponentForFocusOwner(win, newFocusComponent);
		if (dockableComponent == null) {
			return;
		}

		if (!ensureDockableComponentContainsFocusOwner(newFocusComponent, dockableComponent)) {
			// This implies we have made a call that will change the focus, which means
			// will be back here again or we are in some special case and we do not want to 
			// do any more focus work
			return;
		}

		ComponentPlaceholder placeholder = dockableComponent.getComponentWindowingPlaceholder();
		if (placeholder == null) {
			return; // it's been disposed
		}

		pendingRequestFocusComponent = null;

		dockableComponent.setFocusedComponent(newFocusComponent); // for posterity 

		// Note: do this later, since, during this callback, component providers can do 
		//       things that break focus (e.g., launch a modal dialog).  By doing this later, 
		//       it gives the java focus engine a chance to get in the correct state.
		SystemUtilities.runSwingLater(() -> setFocusedComponent(placeholder));
	}

	private boolean ensureDockableComponentContainsFocusOwner(Component newFocusComponent,
			DockableComponent dockableComponent) {

		if (isFocusComponentInEditingWindow(newFocusComponent)) {
			return false;
		}

		if (!SwingUtilities.isDescendingFrom(newFocusComponent, dockableComponent)) {
			dockableComponent.requestFocus();
			return false;
		}
		return true;
	}

	private boolean isFocusComponentInEditingWindow(Component newFocusComponent) {
		if (editWindow == null) {
			return false;
		}

		return SwingUtilities.isDescendingFrom(newFocusComponent, editWindow);
	}

	private DockableComponent getDockableComponentForFocusOwner(Window window,
			Component focusedComp) {
		DockableComponent dockableComponent = getDockableComponent(focusedComp);
		if (dockableComponent != null) {
			return dockableComponent;
		}

		// else use last focus component in window
		WindowNode node = root.getNodeForWindow(window);
		if (node == null) {
			throw new AssertException("Cant find node for window!!");
		}

		// NOTE: We only allow focus within a window on a component that belongs to within a
		//       DockableComponent hierarchy.  If we get here, then we have some component trying
		//       to take focus outside of such a hierarchy.  In this case, we will take focus from
		//       the currently focused component and give it to one of our DockableComponents.
		ComponentPlaceholder placeHolder = node.getLastFocusedProviderInWindow();
		if (placeHolder != null) {
			return placeHolder.getComponent();
		}

		return null;
	}

	private DockableComponent getDockableComponent(Component comp) {
		while (comp != null) {
			if (comp instanceof DockableComponent) {
				return (DockableComponent) comp;
			}
			if (comp instanceof EditWindow) {
				return getDockableComponent(((EditWindow) comp).getAssociatedComponent());
			}
			comp = comp.getParent();
		}

		return null;
	}

	private Element savePreferencesToXML() {
		Element toolPreferencesElement = new Element(TOOL_PREFERENCES_XML_NAME);

		Set<Entry<String, PreferenceState>> entrySet = preferenceStateMap.entrySet();
		for (Entry<String, PreferenceState> entry : entrySet) {
			String key = entry.getKey();
			PreferenceState state = entry.getValue();
			Element preferenceElement = state.saveToXml();
			preferenceElement.setAttribute("NAME", key);
			toolPreferencesElement.addContent(preferenceElement);
		}

		return toolPreferencesElement;
	}

	private void restorePreferencesFromXML(Element rootElement) {
		Element toolPreferencesElement = rootElement.getChild(TOOL_PREFERENCES_XML_NAME);
		if (toolPreferencesElement == null) {
			return;
		}

		List<?> children =
			toolPreferencesElement.getChildren(PreferenceState.PREFERENCE_STATE_NAME);
		for (Object name : children) {
			Element preferencesElement = (Element) name;
			preferenceStateMap.put(preferencesElement.getAttribute("NAME").getValue(),
				new PreferenceState(preferencesElement));
		}
	}

	/**
	 * Adds a PreferenceState object to this window manager instance that is bound to the given
	 * key.  When the state of the tool using this window manager is saved, then the mapped
	 * preferences will also be saved.
	 * @param key The key with which to store the preferences. 
	 * @param state The state object to store.
	 * @see #getPreferenceState(String)
	 */
	public void putPreferenceState(String key, PreferenceState state) {
		if (key == null) {
			throw new IllegalArgumentException("Key is null!");
		}

		preferenceStateMap.put(key, state);
	}

	/**
	 * Gets a preferences state object stored with the given key.  The state objects are loaded
	 * from persistent storage when the tool using this window manager has its state loaded.
	 * @param key The key with which to store the preferences.
	 * @return the PrefrenceState object stored by the given key, or null if one does not exist
	 * @see #putPreferenceState(String, PreferenceState)
	 */
	public PreferenceState getPreferenceState(String key) {
		return preferenceStateMap.get(key);
	}

	/**
	 * Removes the Preferences state for the given key.
	 * @param key the key to the preference state to be removed
	 */
	public void removePreferenceState(String key) {
		preferenceStateMap.remove(key);
	}

	private boolean isMyWindow(Window win) {
		if (root == null) {
			return false;
		}
		if (root.getMainWindow() == win) {
			return true;
		}
		Iterator<DetachedWindowNode> iter = root.getDetachedWindows().iterator();
		while (iter.hasNext()) {
			if (iter.next().getWindow() == win) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Shows the dialog using the tool's currently active window as a parent
	 * 
	 * @param dialogComponent the DialogComponentProvider object to be shown in a dialog
	 */
	public static void showDialogOnActiveWindow(DialogComponentProvider dialogComponent) {
		showDialog(null, dialogComponent, (Component) null);
	}

	/**
	 * Shows the dialog using the tool's currently active window as a parent
	 * 
	 * @param dialogComponent the DialogComponentProvider object to be shown in a dialog
	 */
	public static void showDialog(DialogComponentProvider dialogComponent) {
		showDialogOnActiveWindow(dialogComponent);
	}

	/**
	 * Shows the dialog using the given component's parent frame, centering the dialog 
	 * on the given component
	 * 
	 * @param dialogComponent the DialogComponentProvider object to be shown in a dialog.
	 * @param centeredOnComponent the component on which to center the dialog.
	 */
	public static void showDialog(DialogComponentProvider dialogComponent,
			Component centeredOnComponent) {
		Window parent = null;
		Component c = centeredOnComponent;
		while (c != null) {
			if ((c instanceof Frame) || (c instanceof Dialog)) {
				parent = (Window) c;
				break;
			}
			c = c.getParent();
		}

		showDialog(parent, dialogComponent, centeredOnComponent);
	}

	/**
	 * Shows the dialog using the window containing the given componentProvider as its 
	 * parent window
	 * 
	 * @param dialogComponent the DialogComponentProvider object to be shown in a dialog.
	 * @param centeredOnProvider the component provider that is used to find a parent 
	 *        window for this dialog.   The dialog is centered on this component 
	 *        provider's component.
	 */
	public void showDialog(DialogComponentProvider dialogComponent,
			ComponentProvider centeredOnProvider) {
		ComponentPlaceholder placeholder = getActivePlaceholder(centeredOnProvider);
		Component c = null;
		Window parent = null;
		if (placeholder != null) {
			parent = root.getWindow(placeholder);
			c = placeholder.getComponent();
		}
		showDialog(parent, dialogComponent, c);

	}

	private static void doShowDialog(DialogComponentProvider provider, Component parent,
			Component centeredOnComponent) {

		Runnable r = () -> {
			if (provider.isVisible()) {
				provider.toFront();
				return;
			}

			Window updatedParent = getParentWindow(parent);
			Component updatedCenter = getCenterOnComponent(centeredOnComponent);
			DockingDialog dialog =
				DockingDialog.createDialog(updatedParent, provider, updatedCenter);
			dialog.setVisible(true);
		};

		if (provider.isModal()) {
			SystemUtilities.runSwingNow(r);
		}
		else {
			SystemUtilities.runIfSwingOrPostSwingLater(r);
		}
	}

	private static Component getCenterOnComponent(Component centeredOnComponent) {

		if (centeredOnComponent != null) {
			return centeredOnComponent;
		}

		// by default, prefer to center over the active window
		Window activeWindow = getActiveNonTransientWindow();
		return activeWindow;
	}

	/**
	 * Shows the dialog using the given parent component to find a parent window and to 
	 * position the dialog. If a Window can be found containing the given component, it 
	 * will be used as the parent window for the dialog.  If the component is null or not 
	 * contained in a window, the current active window manager will be used to parent 
	 * the dialog.  If there are no active tools, then a frame will be created to parent
	 * the dialog.
	 * 
	 * @param parent the component whose window over which the given dialog will be shown; null
	 *        signals to use the active window
	 * @param dialogComponent the DialogComponentProvider object to be shown in a dialog.
	 * @see #getParentWindow(Component) for parenting notes
	 */
	public static void showDialog(Component parent, DialogComponentProvider dialogComponent) {
		doShowDialog(dialogComponent, parent, null);
	}

	/**
	 * Shows the dialog using the given parent window using the optional component for 
	 * positioning
	 * 
	 * @param parent the component whose window over which the given dialog will be shown
	 * @param dialogComponent the DialogComponentProvider object to be shown in a dialog
	 * @param centeredOnComponent the component over which the dialog will be centered if not null
	 */
	public static void showDialog(Window parent, DialogComponentProvider dialogComponent,
			Component centeredOnComponent) {

		doShowDialog(dialogComponent, parent, centeredOnComponent);
	}

	private static Window getParentWindow(Component parent) {

		/*
		 	Note: Which window should be the parent of the dialog when the user does not specify?
		 	
		 	Some use cases; a dialog is shown from:
		 		1) A toolbar action
		 		2) A component provider's code
		 		3) A dialog provider's code
		 		4) A background thread
		 		
		 	It seems like the parent should be the active window for 1-2.  
		 	Case 3 should probably use the window of the dialog provider.
		 	Case 4 should probably use the main tool frame, since the user may be 
		 	moving between windows while the thread is working.  So, rather than using the 
		 	active window, we can default to the tool's frame.
		 	
		 	We have not yet solidified how we should parent.  This documentation is meant to 
		 	move us towards clarity as we find Use Cases that don't make sense.  (Once we 
		 	finalize our understanding, we should update the javadoc to list exactly where 
		 	the given Dialog Component will be shown.)
		 	
		 	Use Case
		 		A -The user presses an action on a toolbar from a window on screen 1, while the 
		 		   main tool frame is on screen 2.  We want the popup window to appear on screen
		 		   1, not 2.
		 		B -The user presses an action on the toolbar of a Dialog Component.  The popup
		 		   window should appear above the dialog's window and not the main tool frame.
		 		C -The user is working in a modal dialog and presses a button to launch another
		 		   dialog that is:
		 		 	-modal - Java handles this correctly, allowing the new dialog to be used
		 		 	-non-modal - Java prevents the non-modal from being editing if not parented
		 		 	             correctly
		 		 
		 		  
		 	For now, the easiest mental model to use is to always prefer the active window so 
		 	that a dialog will appear in the user's view.  If we find a case where this is 
		 	not desired, then document it here.
		 */

		//
		// Due to Use Case 'C' above, prefer dialogs as parents, so that child non-modal dialogs
		// do not get blocked by modal dialogs.
		//
		if (isNonTransientWindow(parent)) {
			return (Window) parent;
		}

		DockingWindowManager dwm = getActiveInstance();
		Window defaultWindow = dwm != null ? dwm.getRootFrame() : null;

		if (parent == null) {
			Window w = getActiveNonTransientWindow();
			return w == null ? defaultWindow : w;
		}

		Component c = parent;
		while (c != null) {
			if (c instanceof Frame) {
				return (Window) c;
			}
			c = c.getParent();
		}
		return defaultWindow;
	}

	private static boolean isNonTransientWindow(Component c) {
		if (c instanceof DockingDialog) {
			DockingDialog d = (DockingDialog) c;
			DialogComponentProvider provider = d.getComponent();
			if (provider == null) {
				return false; // we have seen this in testing
			}

			if (provider.isTransient()) {
				return false;
			}
		}

		return (c instanceof Window);
	}

	private static Window getActiveNonTransientWindow() {

		KeyboardFocusManager kfm = KeyboardFocusManager.getCurrentKeyboardFocusManager();
		Window activeWindow = kfm.getActiveWindow();
		if (!(activeWindow instanceof DockingDialog)) {
			return activeWindow;
		}

		// We do not want Task Dialogs becoming parents, as they will get closed when the 
		// task is finished, closing any other child dialogs, which means that dialogs such
		// as message dialogs will too be closed
		DockingDialog d = (DockingDialog) activeWindow;
		Window ancestor = SwingUtilities.getWindowAncestor(d);
		if (!d.isShowing()) {
			if (!ancestor.isShowing()) {
				return null;
			}

			return ancestor;
		}

		DialogComponentProvider provider = d.getComponent();
		if (provider.isTransient()) {
			return ancestor;
		}

		return d;
	}

	public ComponentProvider getActiveComponentProvider() {
		if (focusedPlaceholder != null) {
			return focusedPlaceholder.getProvider();
		}
		return null;
	}

	/**
	 * Sets the icon for this window's 'home button'. This button, when pressed,
	 * will show the tool's main application window.
	 * 
	 * @param icon the button's icon 
	 * @param callback the callback to execute when the button is pressed by the user
	 */
	public void setHomeButton(Icon icon, Runnable callback) {
		root.setHomeButton(icon, callback);
	}

	/**
	 * Returns true if a status bar is present.
	 * @return true if a status bar is present.
	 */
	public boolean hasStatusBar() {
		return hasStatusBar;
	}

	/**
	 * Add a new status item component to the status area.  The preferred height and border
	 * for the component will be altered.  The components preferred width will be 
	 * preserved.
	 * @param c the status item component to add
	 * @param addBorder True signals to add a border to the status area
	 * @param rightSide component will be added to the right-side of the status
	 * area if true, else it will be added immediately after the status text area
	 * if false.
	 */
	public void addStatusItem(JComponent c, boolean addBorder, boolean rightSide) {
		root.addStatusItem(c, addBorder, rightSide);
	}

	/**
	 * Remove the specified status item.
	 * @param c status component previously added.
	 */
	public void removeStatusItem(JComponent c) {
		root.removeStatusItem(c);
	}

	/**
	 * Set the status text in the active component window.
	 * @param text status text
	 */
	public void setStatusText(String text) {
		if (root != null) {
			root.setStatusText(text);
		}
	}

	/**
	 * Set the menu group associated with a cascaded submenu.  This allows
	 * a cascading menu item to be grouped with a specific set of actions.
	 * The default group for a cascaded submenu is the name of the submenu.
	 * @param menuPath menu name path where the last element corresponds 
	 * to the specified group name.
	 * @param group group name
	 */
	public void setMenuGroup(String[] menuPath, String group) {
		doSetMenuGroup(menuPath, group);
		scheduleUpdate();
	}

	/*
	 * A version of setMenuGroup() that does *not* trigger an update.   When clients call the 
	 * public API, an update is needed.  This method is used during the rebuilding process
	 * when we know that an update is not need, as we are in the middle of an update.
	 */
	void doSetMenuGroup(String[] menuPath, String group) {
		actionManager.setMenuGroup(menuPath, group);
	}

	/**
	 * Set the menu group associated with a cascaded submenu.  This allows
	 * a cascading menu item to be grouped with a specific set of actions.
	 * <p>
	 * The default group for a cascaded submenu is the name of the submenu.
	 * <p>
	 * 
	 * @param menuPath menu name path where the last element corresponds to the specified group name.
	 * @param group group name
	 * @param menuSubGroup the name used to sort the cascaded menu within other menu items at 
	 *                     its level 
	 */
	public void setMenuGroup(String[] menuPath, String group, String menuSubGroup) {
		actionManager.setMenuGroup(menuPath, group, menuSubGroup);
		scheduleUpdate();
	}

	/**
	 * Tests if the given component is one of a known list of component classes that we
	 * don't ever want to get keyboard focus.  Currently excluded is JScrollPane
	 * @param c the component to test for exclusion
	 * @return true if the component should not be allowed to have keyboard focus.
	 */
	static boolean excludeFocus(Component c) {
		return (c instanceof JScrollPane) || (c instanceof JScrollBar) ||
			(c instanceof JTabbedPane);
	}

	/**
	 * Sets the mode such that all satellite docking windows always appear on top of the root window
	 * @param windowsOnTop true to set mode to on top, false to disable on top mode.
	 */
	public void setWindowsOnTop(boolean windowsOnTop) {
		this.windowsOnTop = windowsOnTop;
		root.updateDialogs();
	}

	/**
	 * Returns true if the window mode is "satellite windows always on top of root window".
	 * @return true if the window mode is "satellite windows always on top of root window".
	 */
	public boolean isWindowsOnTop() {
		return windowsOnTop;
	}

	/**
	 * Returns a list with all the windows in the windowStack. Used for testing.
	 * @param includeMain if true, include the main root window.
	 * @return a list with all the windows in the windowStack. Used for testing.
	 */
	public List<Window> getWindows(boolean includeMain) {
		ArrayList<Window> winList = new ArrayList<>();
		if (includeMain) {
			winList.add(root.getMainWindow());
		}
		Iterator<DetachedWindowNode> it = root.getDetachedWindows().iterator();
		while (it.hasNext()) {
			DetachedWindowNode node = it.next();
			Window win = node.getWindow();
			if (win != null) {
				winList.add(win);
			}
		}
		return winList;
	}

	void iconify() {
		List<Window> winList = getWindows(false);
		Iterator<Window> it = winList.iterator();
		while (it.hasNext()) {
			Window w = it.next();
			if (w instanceof Frame) {
				w.setVisible(false);
			}
		}
	}

	void deIconify() {
		List<Window> winList = getWindows(false);
		Iterator<Window> it = winList.iterator();
		while (it.hasNext()) {
			Window w = it.next();
			if (w instanceof Frame) {
				w.setVisible(true);
			}
		}
	}

	/**
	 * Returns the root window.
	 * @return the root window.
	 */
	public Window getMainWindow() {
		return root.getMainWindow();
	}

	public static DockingActionIf getMouseOverAction() {
		return actionUnderMouse;
	}

	public static void setMouseOverAction(DockingActionIf action) {
		actionUnderMouse = action;
	}

	public static Object getMouseOverObject() {
		return objectUnderMouse;
	}

	public static void setMouseOverObject(Object object) {
		objectUnderMouse = object;
	}

	public static void clearMouseOverHelp() {
		actionUnderMouse = null;
		objectUnderMouse = null;
	}

	public void contextChanged(ComponentProvider provider) {
		if (provider == null) {
			actionManager.contextChangedAll(); // update all windows;
			return;
		}

		ComponentPlaceholder placeHolder = getActivePlaceholder(provider);
		if (placeHolder == null) {
			return;
		}
		placeHolder.contextChanged();
		actionManager.contextChanged(placeHolder);
	}

	public void addContextListener(DockingContextListener listener) {
		contextListeners.add(listener);
	}

	public void removeContextListener(DockingContextListener listener) {
		contextListeners.remove(listener);
	}

	public void notifyContextListeners(ComponentPlaceholder placeHolder,
			ActionContext actionContext) {

		if (placeHolder == focusedPlaceholder) {
			for (DockingContextListener listener : contextListeners) {
				listener.contextChanged(actionContext);
			}
		}
	}

	/**
	 * Registers a callback to be notified when the given component has been parented to
	 * a docking window manager.
	 * @param component the component that will be parented in a docking window system.
	 * @param listener the listener to be notified the component was parented.
	 */
	public static void registerComponentLoadedListener(final Component component,
			final ComponentLoadedListener listener) {

		// We want to load our state after the column model is loaded.  We are using this
		// listener to know when the table has been added to the component hierarchy, as its 
		// model has been loaded by then.
		component.addHierarchyListener(new HierarchyListener() {
			@Override
			public void hierarchyChanged(HierarchyEvent e) {
				long changeFlags = e.getChangeFlags();
				if (HierarchyEvent.DISPLAYABILITY_CHANGED == (changeFlags &
					HierarchyEvent.DISPLAYABILITY_CHANGED)) {

					// check for the first time we are put together
					boolean isDisplayable = component.isDisplayable();
					if (isDisplayable) {
						component.removeHierarchyListener(this);
						DockingWindowManager windowManager = getInstance(component);
						listener.componentLoaded(windowManager);
					}
				}
			}
		});

	}

//==================================================================================================
// Inner Classes
//==================================================================================================	

	/**
	 * A class that tracks placeholders that are activated (brought to the front).  If a 
	 * placeholder is activated too frequently, this class will emphasize that window, under the
	 * assumption that the user doesn't see the window.
	 */
	private class ActivatedInfo {

		private long lastCalledTimestamp;
		private ComponentPlaceholder lastActivatedPlaceholder;

		void activated(ComponentPlaceholder placeholder) {

			if (lastActivatedPlaceholder == placeholder) {
				// repeat call--see if it was quickly called again (a sign of confusion/frustration)
				long elapsedTime = System.currentTimeMillis() - lastCalledTimestamp;
				if (elapsedTime < 3000) { // somewhat arbitrary time window
					placeholder.emphasize();
				}
			}
			else {
				this.lastActivatedPlaceholder = placeholder;
			}
			lastCalledTimestamp = System.currentTimeMillis();
		}
	}
}
