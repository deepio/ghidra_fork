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
package docking.widgets.tree;

import static docking.widgets.tree.support.GTreeSelectionEvent.EventOrigin.USER_GENERATED;
import static ghidra.util.SystemUtilities.runSwingNow;

import java.awt.*;
import java.awt.dnd.Autoscroll;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.io.PrintWriter;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import javax.swing.*;
import javax.swing.Timer;
import javax.swing.event.*;
import javax.swing.tree.*;

import docking.DockingWindowManager;
import docking.widgets.JTreeMouseListenerDelegate;
import docking.widgets.filter.FilterTextField;
import docking.widgets.table.AutoscrollAdapter;
import docking.widgets.tree.internal.*;
import docking.widgets.tree.support.*;
import docking.widgets.tree.support.GTreeSelectionEvent.EventOrigin;
import docking.widgets.tree.tasks.*;
import ghidra.util.FilterTransformer;
import ghidra.util.SystemUtilities;
import ghidra.util.exception.CancelledException;
import ghidra.util.task.*;
import ghidra.util.worker.PriorityWorker;

/**
 * Class for creating a JTree that supports filtering, threading, and a progress bar.
 */

public class GTree extends JPanel implements BusyListener {

	private AutoScrollTree tree;
	private GTreeModel model;

	/**
	 * This is the root node that either is the actual current root node, or the node that will
	 * be the real root node, once the Worker has loaded it.  Thus, it is possible that a call to
	 * {@link GTreeModel#getRoot()} will return an {@link InProgressGTreeRootNode}.  By keeping
	 * this variable around, we can give this node to clients, regardless of the root node
	 * visible in the tree.
	 */
	private GTreeRootNode realRootNode;

	private JScrollPane scrollPane;
	private GTreeRenderer renderer;

	private FilterTransformer<GTreeNode> transformer = new DefaultGTreeDataTransformer();

	private JTreeMouseListenerDelegate mouseListenerDelegate;
	private GTreeDragNDropHandler dragNDropHandler;
	private boolean isFilteringEnabled = true;
	private boolean hasFilterText = false;

	private AtomicLong modificationID = new AtomicLong();
	private ThreadLocal<TaskMonitor> threadLocalMonitor = new ThreadLocal<>();
	private PriorityWorker worker;
	private Timer showTimer;

	private TaskMonitorComponent monitor;
	private JComponent progressPanel;

	private JPanel mainPanel;

	private GTreeState restoreTreeState;
	private GTreeFilterTask lastFilterTask;
	private String uniquePreferenceKey;

	private GTreeFilter filter;
	private GTreeFilterProvider filterProvider;
	private List<GTreeNode> nodesToBeFiltered = new ArrayList<>();
	private SwingUpdateManager filterUpdateManager;
	private int MAX_BUFFERED_FILTERED = 10;

	/**
	 * Creates a GTree with the given root node.  The created GTree will use a threaded model
	 * for performing tasks, which allows the GUI to be responsive for reaaaaaaaaly big trees.
	 *
	 * @param root The root node of the tree.
	 */
	public GTree(GTreeRootNode root) {
		uniquePreferenceKey = generateFilterPreferenceKey();
		this.realRootNode = root;
		monitor = new TaskMonitorComponent();
		monitor.setShowProgressValue(false);// the tree's progress is fabricated--don't paint it
		worker = new PriorityWorker("GTree Worker", monitor);
		root.setGTree(this);
		this.model = new GTreeModel(root);
		worker.setBusyListener(this);
		init();

		DockingWindowManager.registerComponentLoadedListener(this,
			windowManager -> filterProvider.loadFilterPreference(windowManager,
				uniquePreferenceKey));

		filterUpdateManager = new SwingUpdateManager(1000, 30000, () -> performNodeFiltering());
	}

	/**
	 * Should be called by threads running {@link GTreeTask}s.
	 *
	 * @param monitor the monitor being used for the currently running task.
	 * @see #getThreadLocalMonitor()
	 */
	void setThreadLocalMonitor(TaskMonitor monitor) {
		threadLocalMonitor.set(monitor);
	}

	/**
	 * Returns the monitor in associated with the GTree for the calling thread.  This method is
	 * designed to be used by slow loading nodes that are loading <b>off the Swing thread</b>.
	 * Some of the loading methods are called by the slow loading node at a point when it is
	 * not passed a monitor (like when clients ask how many children the node has).
	 * <p>
	 * When a {@link GTreeTask} is run in thread from a thread pool, it registers its monitor
	 * (which is different than the GTree's) with this tree.  Then, if a node performing work,
	 * like loading, needs a monitor, it can call {@link #getThreadLocalMonitor()} in order to
	 * get the monitor that was registered with that thread.
	 * <P>
	 * This method is necessary because the concurrent library used by this tree will provide a
	 * unique monitor for each task that is run, which will be different (but connected) to the
	 * monitor created by this tree.
	 * <p>
	 * If this method is called from a client other than a {@link GTreeTask}, then a dummy
	 * monitor will be returned.
	 *
	 * @return the monitor associated with the calling thread; null if the monitor was not set
	 * @see #setThreadLocalMonitor(TaskMonitor)
	 */
	TaskMonitor getThreadLocalMonitor() {
		TaskMonitor localMonitor = threadLocalMonitor.get();
		if (localMonitor != null) {
			return localMonitor;
		}

		return TaskMonitorAdapter.DUMMY_MONITOR;
	}

	@Override
	public void setEnabled(boolean enabled) {
		super.setEnabled(enabled);
		tree.setEnabled(enabled);
		scrollPane.setEnabled(enabled);
		filterProvider.setEnabled(enabled);
	}

	public void setDragNDropHandler(GTreeDragNDropHandler dragNDropHandler) {
		this.dragNDropHandler = dragNDropHandler;
		new GTreeDragNDropAdapter(this, tree, dragNDropHandler);
	}

	@Override
	public void setTransferHandler(TransferHandler handler) {
		tree.setTransferHandler(handler);
	}

	public GTreeDragNDropHandler getDragNDropHandler() {
		return dragNDropHandler;
	}

	private void init() {
		model.addTreeModelListener(new TreeModelListener() {
			@Override
			public void treeStructureChanged(TreeModelEvent e) {
				modificationID.incrementAndGet();
			}

			@Override
			public void treeNodesRemoved(TreeModelEvent e) {
				modificationID.incrementAndGet();
			}

			@Override
			public void treeNodesInserted(TreeModelEvent e) {
				modificationID.incrementAndGet();
			}

			@Override
			public void treeNodesChanged(TreeModelEvent e) {
				// don't care
			}
		});
		model.addTreeModelListener(new FilteredExpansionListener());

		tree = new AutoScrollTree(model);
		tree.setRowHeight(-1);// variable size rows
		tree.setSelectionModel(new GTreeSelectionModel());
		tree.setInvokesStopCellEditing(true);// clicking outside the cell editor will trigger a save, not a cancel
		docking.ToolTipManager.sharedInstance().registerComponent(tree);

		setLayout(new BorderLayout());

		scrollPane = new JScrollPane(tree);

		mainPanel = new JPanel(new BorderLayout());
		mainPanel.add(scrollPane, BorderLayout.CENTER);

		add(mainPanel, BorderLayout.CENTER);
		renderer = new GTreeRenderer();
		tree.setCellRenderer(renderer);
		tree.setCellEditor(new GTreeCellEditor(tree, renderer));
		tree.setEditable(true);

		addGTreeSelectionListener(e -> {
			if (e.getEventOrigin() == GTreeSelectionEvent.EventOrigin.USER_GENERATED ||
				e.getEventOrigin() == GTreeSelectionEvent.EventOrigin.API_GENERATED) {
				restoreTreeState = getTreeState();
			}
		});

		mouseListenerDelegate = createMouseListenerDelegate();
		filterProvider = new DefaultGTreeFilterProvider(this);
		add(filterProvider.getFilterComponent(), BorderLayout.SOUTH);
	}

	public void setCellRenderer(GTreeRenderer renderer) {
		this.renderer = renderer;
		tree.setCellRenderer(renderer);
	}

	public GTreeRenderer getCellRenderer() {
		return renderer;
	}

	public void dispose() {
		filterUpdateManager.dispose();
		worker.dispose();
		GTreeRootNode root = model.getModelRoot();
		if (root != null) {
			root.dispose();
		}
		realRootNode.dispose();// just in case we were loading
		model.dispose();
	}

	public boolean isDisposed() {
		return worker.isDisposed();
	}

	/**
	 * Signals that any multithreaded work should be cancelled.
	 */
	public void cancelWork() {
		worker.clearAllJobs();
	}

	public void filterChanged() {
		updateModelFilter();
	}

	protected void updateModelFilter() {
		filter = filterProvider.getFilter();

		modificationID.incrementAndGet();

		if (lastFilterTask != null) {
			// it is safe to repeatedly call cancel
			lastFilterTask.cancel();
		}

		lastFilterTask = new GTreeFilterTask(this, getRootNode(), filter);

		if (isFilteringEnabled()) {
			worker.schedule(lastFilterTask);
		}
	}

// TODO: doc on how to override to extend listener stuff
	protected JTreeMouseListenerDelegate createMouseListenerDelegate() {
		return new GTreeMouseListenerDelegate(tree, this);
	}

	public GTreeState getRestoreTreeState() {
		return restoreTreeState;
	}

	/**
	 * Returns a state object that allows this tree to later restore its expanded and selected
	 * state.
	 * <p>
	 * <b>Note: </b>See the usage note at the header of this class concerning how tree state
	 * is used relative to the <tt>equals()</tt> method.
	 */
	public GTreeState getTreeState() {
		return new GTreeState(this);
	}

	public GTreeState getTreeState(GTreeNode node) {
		return new GTreeState(this, node);
	}

	/**
	 * Restores the expanded and selected state of this tree to that contained in the given
	 * state object.
	 * <p>
	 * <b>Note: </b>See the usage note at the header of this class concerning how tree state
	 * is used relative to the <tt>equals()</tt> method.
	 *
	 * @see #getTreeState()
	 * @see #getTreeState(GTreeNode)
	 * @see #cloneTreeState()
	 */
	public void restoreTreeState(GTreeState state) {
		runTask(new GTreeRestoreTreeStateTask(this, state));
	}

	/**
	 * A method that subclasses can use to be notified when tree state has been restored.  This
	 * method is called after a major structural tree change has happened <b>and</b> the paths
	 * that should be opened have been opened.  Thus any other nodes are closed and can be
	 * disposed, if desired.
	 *
	 * @param taskMonitor
	 */
	public void expandedStateRestored(TaskMonitor onitor) {
		// optional
	}

	public List<TreePath> getExpandedPaths(GTreeNode node) {
		Enumeration<TreePath> expandedPaths = tree.getExpandedDescendants(node.getTreePath());
		if (expandedPaths == null) {
			return Collections.emptyList();
		}
		return Collections.list(expandedPaths);
	}

	public void expandTree(GTreeNode node) {
		runTask(new GTreeExpandAllTask(this, node));
	}

	public void expandAll() {
		runTask(new GTreeExpandAllTask(this, getRootNode()));
	}

	public void collapseAll(GTreeNode node) {

		runSwingNow(() -> {
			node.fireNodeStructureChanged(node);
			tree.collapsePath(node.getTreePath());

			boolean nodeIsRoot = node.equals(model.getRoot());

			if (nodeIsRoot && !tree.isRootAllowedToCollapse()) {
				runTask(new GTreeExpandNodeToDepthTask(this, getJTree(), node, 1));
			}

		});
	}

	public void expandPath(GTreeNode node) {
		expandPaths(new TreePath[] { node.getTreePath() });
	}

	public void expandPath(TreePath path) {
		expandPaths(new TreePath[] { path });
	}

	public void expandPaths(TreePath[] paths) {
		runTask(new GTreeExpandPathsTask(this, tree, Arrays.asList(paths)));
	}

	public void expandPaths(List<TreePath> pathsList) {
		TreePath[] treePaths = pathsList.toArray(new TreePath[pathsList.size()]);
		expandPaths(treePaths);
	}

	public void clearSelectionPaths() {
		runTask(new GTreeClearSelectionTask(this, tree));
	}

	public void setSelectedNode(GTreeNode node) {
		setSelectionPaths(new TreePath[] { node.getTreePath() });
	}

	public void setSelectedNodes(GTreeNode... nodes) {
		List<TreePath> paths = new ArrayList<>();
		for (GTreeNode node : nodes) {
			paths.add(node.getTreePath());
		}
		setSelectionPaths(paths);
	}

	public void setSelectedNodes(Collection<GTreeNode> nodes) {
		List<TreePath> paths = new ArrayList<>();
		for (GTreeNode node : nodes) {
			paths.add(node.getTreePath());
		}
		setSelectionPaths(paths);
	}

	public void setSelectionPaths(TreePath[] paths) {
		setSelectionPaths(paths, EventOrigin.API_GENERATED);
	}

	public void setSelectionPaths(List<TreePath> pathsList) {
		TreePath[] treePaths = pathsList.toArray(new TreePath[pathsList.size()]);
		setSelectionPaths(treePaths, EventOrigin.API_GENERATED);
	}

	public void setSelectionPath(TreePath path) {
		setSelectionPaths(new TreePath[] { path });
	}

	/**
	 * A convenience method to select a node by a path, starting with the tree root name, down
	 * each level until the desired node name.
	 *
	 * @param namePath The path to select
	 */
	public void setSelectedNodeByNamePath(String[] namePath) {
		runTask(new GTreeSelectNodeByNameTask(this, tree, namePath, EventOrigin.API_GENERATED));
	}

	/**
	 * A convenience method that allows clients that have created a new child node to select that
	 * node in the tree, without having to lookup the actual GTreeNode implementation.
	 *
	 * @param parentNode The parent containing a child by the given name
	 * @param childName The name of the child to select
	 */
	public void setSeletedNodeByName(GTreeNode parentNode, String childName) {
		TreePath treePath = parentNode.getTreePath();
		TreePath pathWithChild = treePath.pathByAddingChild(childName);
		setSelectedNodeByPathName(pathWithChild);
	}

	/**
	 * Selects the node that matches the each name in the given tree path.  It is worth noting
	 * that the items in the tree path themselves are not used to identify nodes, but the
	 * {@link #toString()} of those items will be used.
	 *
	 * @param treePath The path containing the names of the path of the node to select
	 */
	public void setSelectedNodeByPathName(TreePath treePath) {
		Object[] path = treePath.getPath();
		String[] namePath = new String[treePath.getPathCount()];
		for (int i = 0; i < path.length; i++) {
			namePath[i] = path[i].toString();
		}

		runTask(new GTreeSelectNodeByNameTask(this, tree, namePath, EventOrigin.API_GENERATED));
	}

	public void setSelectionPaths(TreePath[] path, EventOrigin origin) {
		runTask(new GTreeSelectPathsTask(this, tree, Arrays.asList(path), origin));
	}

	public boolean isCollapsed(TreePath path) {
		return tree.isCollapsed(path);
	}

	public void setHorizontalScrollPolicy(int policy) {
		scrollPane.setHorizontalScrollBarPolicy(policy);
	}

	protected JScrollPane getScrollPane() {
		return scrollPane;
	}

	/**
	 * Sets the size of the scroll when mouse scrolling or pressing the scroll up/down buttons.
	 * Most clients will not need this method, as the default behavior of the tree is correct,
	 * which is to scroll based upon the size of the nodes (which is usually uniform and a
	 * single row in size).  However, some clients that have variable row height, with potentially
	 * large rows, may wish to change the scrolling behavior so that it is not too fast.
	 *
	 * @param increment the new (uniform) scroll increment.
	 */
	public void setScrollableUnitIncrement(int increment) {
		tree.setScrollableUnitIncrement(increment);
	}

	protected GTreeModel getModel() {
		return model;
	}

	// don't let classes outside this package ever have access to the JTree.  It would allow
	// subclasses to break various assumptions about the state of the tree. For example, we
	// assume the TreeSelectionModel is really a GTreeSelectionModel.
	protected final JTree getJTree() {
		return tree;
	}

	public Point getViewPosition() {
		JViewport viewport = scrollPane.getViewport();
		Point p = viewport.getViewPosition();
		return p;
	}

	public void setViewPosition(Point p) {
		JViewport viewport = scrollPane.getViewport();
		viewport.setViewPosition(p);
	}

	public Rectangle getViewRect() {
		JViewport viewport = scrollPane.getViewport();
		Rectangle viewRect = viewport.getViewRect();
		return viewRect;
	}

	public GTreeNode getNodeForLocation(int x, int y) {
		TreePath pathForLocation = tree.getPathForLocation(x, y);
		if (pathForLocation != null) {
			return (GTreeNode) pathForLocation.getLastPathComponent();
		}
		return null;
	}

	/**
	 * Gets the node for the given path.  This is useful if the node that is in the path has
	 * been replaced by a new node that is equal, but a different instance.
	 * 
	 * @param path the path of the node
	 * @return the current node in the tree
	 */
	public GTreeNode getNodeForPath(TreePath path) {
		if (path == null) {
			return null;
		}

		if (path.getPathCount() == 1) {
			Object lastPathComponent = path.getLastPathComponent();
			GTreeNode rootNode = getRootNode();
			if (rootNode.equals(lastPathComponent)) {
				return rootNode;
			}
			return null; // invalid path--the root of the path is not equal to our root!
		}

		GTreeNode parentNode = getNodeForPath(path.getParentPath());
		if (parentNode == null) {
			return null; // must be a path we don't have
		}

		Object lastPathComponent = path.getLastPathComponent();
		List<GTreeNode> children = parentNode.getChildren();
		for (GTreeNode child : children) {
			if (child.equals(lastPathComponent)) {
				return child;
			}
		}
		return null;
	}

	public void setActiveDropTargetNode(GTreeNode node) {
		renderer.setRendererDropTarget(node);
	}

	public void setFilterText(String text) {
		filterProvider.setFilterText(text);
	}

	public GTreeFilterProvider getFilterProvider() {
		return filterProvider;
	}

	public void setFilterProvider(GTreeFilterProvider filterProvider) {
		this.filterProvider = filterProvider;
		removeAll();
		add(mainPanel, BorderLayout.CENTER);
		JComponent filterComponent = filterProvider.getFilterComponent();
		if (filterComponent != null) {
			add(filterComponent, BorderLayout.SOUTH);
		}
		filterProvider.setDataTransformer(transformer);
		updateModelFilter();
	}

	public String getFilterText() {
		return filterProvider.getFilterText();
	}

	/**
	 * Disabled the filter text field, but allows the tree to still filter.  This is useful if
	 * you want to allow programmatic filtering, but to not allow the user to filter.
	 *
	 * @param enabled True makes the filter field editable; false makes it uneditable
	 * @see #setFilteringEnabled(boolean)
	 */
	public void setFilterFieldEnabled(boolean enabled) {
		filterProvider.setEnabled(enabled);
	}

	/**
	 * Disables all filtering performed by this tree.  Also, the filter field of the tree will
	 * be disabled.
	 * <p>
	 * Use this method to temporarily disable filtering.
	 *
	 * @param enabled True to allow normal filtering; false to disable all filtering
	 * @see #setFilterFieldEnabled(boolean)
	 */
	public void setFilteringEnabled(boolean enabled) {
		isFilteringEnabled = enabled;
		setFilterFieldEnabled(enabled);
		validate();
		refilter();
	}

	/**
	 * Hides the filter field.  Filtering will still take place, as defined by the
	 * {@link GTreeFilterProvider}.
	 *
	 * @param visible true to show the filter; false to hide it.
	 * @see #setFilteringEnabled(boolean)
	 */
	public void setFilterVisible(boolean visible) {
		JComponent filterComponent = filterProvider.getFilterComponent();
		filterComponent.setVisible(visible);
		validate();
	}

	public boolean isFilteringEnabled() {
		return isFilteringEnabled;
	}

	/**
	 * Sets a transformer object used to perform filtering.  This object is responsible for
	 * turning the tree's nodes into a list of strings that can be searched when filtering.
	 *
	 * @param transformer the transformer to set
	 */
	public void setDataTransformer(FilterTransformer<GTreeNode> transformer) {
		filterProvider.setDataTransformer(transformer);
	}

	/**
	 * Returns the filter text field in this tree.
	 *
	 * @return the filter text field in this tree.
	 */
	public Component getFilterField() {
		JComponent filterComponent = filterProvider.getFilterComponent();
		if (filterComponent != null) {
			Component[] components = filterComponent.getComponents();
			for (Component component : components) {
				if (component instanceof FilterTextField) {
					return component;
				}
			}
			return filterComponent;
		}
		return tree;
	}

	/**
	 * Returns true if the given JTree is the actual JTree used by this GTree.
	 * 
	 * @param jTree the tree to test
	 * @return true if the given JTree is the actual JTree used by this GTree.
	 */
	public boolean isMyJTree(JTree jTree) {
		return tree == jTree;
	}

	/**
	 * Sets the root node for the GTree.
	 * <p>
	 * Note: If this call is made from the Swing thread, then it will install a temporary
	 * "In Progress" node and then return immediately.  However, when called from any other thread,
	 * this method will block while any pending work is cancelled.  In this scenario, when this
	 * method returns, the given root node will be the actual root node.
	 *
	 * @param rootNode The node to set.
	 */
	public void setRootNode(GTreeRootNode rootNode) {
		worker.clearAllJobs();
		GTreeRootNode root = model.getModelRoot();
		root.dispose();

		this.realRootNode = rootNode;
		rootNode.setGTree(this);

		//
		// We need to use our standard 'worker pipeline' for mutations to the tree.  This means
		// that requests from the Swing thread must go through the worker.  However,
		// non-Swing-thread requests can just block while we wait for cancelled work to finish
		// and setup the new root.  The assumption is that other threads (like test threads and
		// client background threads) will want to block in order to get real-time data.  Further,
		// since they are not in the Swing thread, blocking will not lock-up the GUI.
		//
		if (SwingUtilities.isEventDispatchThread()) {
			model.setRootNode(new InProgressGTreeRootNode());
			runTask(new SetRootNodeTask(this, rootNode, model));
		}
		else {
			worker.waitUntilNoJobsScheduled(Integer.MAX_VALUE);
			monitor.clearCanceled();
			model.setRootNode(rootNode);
		}
	}

	/**
	 * This method always returns the root node given by the client, whether from the
	 * constructor or from {@link #setRootNode(GTreeRootNode)}.  There is a chance that the
	 * root node being used by the GUI is an "In Progress" node that is a placeholder used while
	 * this threaded tree is setting the root node.
	 * @return
	 */
	public GTreeRootNode getRootNode() {
		return realRootNode;
	}

	/**
	 * This method is useful for debugging tree problems.  Don't know where else to put it.
	 * @param name - Use this to indicate what tree event occurred ("node inserted" "node removed", etc.)
	 * @param e the TreeModelEvent;
	 */
	public static void printEvent(PrintWriter out, String name, TreeModelEvent e) {
		StringBuffer buf = new StringBuffer();
		buf.append(name);
		buf.append("\n\tPath: ");
		Object[] path = e.getPath();
		if (path != null) {
			for (Object object : path) {
				GTreeNode node = (GTreeNode) object;
				buf.append(node.getName() + "(" + node.hashCode() + ")");
				buf.append(",");
			}
		}
		buf.append("\n\t");
		int[] childIndices = e.getChildIndices();
		if (childIndices != null) {
			buf.append("indices [ ");
			for (int index : childIndices) {
				buf.append(Integer.toString(index) + ", ");
			}
			buf.append("]\n\t");
		}
		Object[] children = e.getChildren();
		if (children != null) {
			buf.append("children [ ");
			for (Object child : children) {
				GTreeNode node = (GTreeNode) child;
				buf.append(node.getName() + "(" + node.hashCode() + "), ");
			}
			buf.append("]");
		}
		out.println(buf.toString());
	}

//==================================================================================================
// JTree Pass-through Methods
//==================================================================================================

	public TreeSelectionModel getSelectionModel() {
		return tree.getSelectionModel();
	}

	public GTreeSelectionModel getGTSelectionModel() {
		return (GTreeSelectionModel) tree.getSelectionModel();
	}

	public void setSelectionModel(GTreeSelectionModel selectionModel) {
		tree.setSelectionModel(selectionModel);
	}

	public int getRowCount() {
		return tree.getRowCount();
	}

	public int getRowForPath(TreePath treePath) {
		return tree.getRowForPath(treePath);
	}

	public TreePath getPathForRow(int row) {
		return tree.getPathForRow(row);
	}

	public TreePath getSelectionPath() {
		return tree.getSelectionPath();
	}

	public TreePath[] getSelectionPaths() {
		TreePath[] paths = tree.getSelectionPaths();
		if (paths == null) {
			paths = new TreePath[0];
		}
		return paths;
	}

	public boolean isExpanded(TreePath treePath) {
		return tree.isExpanded(treePath);
	}

	public boolean isPathSelected(TreePath treePath) {
		return tree.isPathSelected(treePath);
	}

	public boolean isRootVisible() {
		return tree.isRootVisible();
	}

	public void setRootVisible(boolean b) {
		tree.setRootVisible(b);
	}

	public void setShowsRootHandles(boolean b) {
		tree.setShowsRootHandles(b);
	}

	public void scrollPathToVisible(TreePath treePath) {
		tree.scrollPathToVisible(treePath);
	}

	public CellEditor getCellEditor() {
		return tree.getCellEditor();
	}

	public TreePath getPathForLocation(int x, int y) {
		return tree.getPathForLocation(x, y);
	}

	public Rectangle getPathBounds(TreePath path) {
		return tree.getPathBounds(path);
	}

	public void setRowHeight(int rowHeight) {
		tree.setRowHeight(rowHeight);
	}

	public void addSelectionPath(TreePath path) {
		tree.addSelectionPath(path);
	}

	public void addTreeExpansionListener(TreeExpansionListener listener) {
		tree.addTreeExpansionListener(listener);
	}

	public void removeTreeExpansionListener(TreeExpansionListener listener) {
		tree.removeTreeExpansionListener(listener);
	}

	public void addGTreeSelectionListener(GTreeSelectionListener listener) {
		GTreeSelectionModel selectionModel = (GTreeSelectionModel) tree.getSelectionModel();
		selectionModel.addGTreeSelectionListener(listener);
	}

	public void removeGTreeSelectionListener(GTreeSelectionListener listener) {
		GTreeSelectionModel selectionModel = (GTreeSelectionModel) tree.getSelectionModel();
		selectionModel.removeGTreeSelectionListener(listener);
	}

	public void addGTModelListener(TreeModelListener listener) {
		tree.getModel().addTreeModelListener(listener);
	}

	public void removeGTModelListner(TreeModelListener listener) {
		tree.getModel().removeTreeModelListener(listener);
	}

	public void setEditable(boolean editable) {
		tree.setEditable(editable);
	}

	public void startEditing(final GTreeNode parent, final String childName) {
		// we call this here, even though the JTree will do this for us, so that we will trigger
		// a load call before this task is run, in case lazy nodes are involved in this tree,
		// which must be loaded before we can edit
		expandPath(parent);
		runTask(new GTreeStartEditingTask(GTree.this, tree, parent, childName));
	}

	@Override
	public synchronized void addMouseListener(MouseListener listener) {
		mouseListenerDelegate.addMouseListener(listener);
	}

	@Override
	public synchronized void removeMouseListener(MouseListener listener) {
		mouseListenerDelegate.removeMouseListener(listener);
	}

	@Override
	public synchronized MouseListener[] getMouseListeners() {
		return mouseListenerDelegate.getMouseListeners();
	}

	public void setCellEditor(TreeCellEditor editor) {
		tree.setCellEditor(editor);
	}

	public boolean isPathEditable(TreePath path) {
		GTreeNode node = (GTreeNode) path.getLastPathComponent();
		return node.isEditable();
	}

	/**
	 * Passing a value of <tt>false</tt> signals to disable the {@link JTree}'s default behavior
	 * of showing handles for leaf nodes until they are opened.
	 *
	 * @param enable False to disable the default JTree behavior
	 */
	public void setPaintHandlesForLeafNodes(boolean enable) {
		tree.setPaintHandlesForLeafNodes(enable);
	}

	public boolean isRootAllowedToCollapse() {
		return tree.isRootAllowedToCollapse();
	}

	public void setRootNodeAllowedToCollapse(boolean allowed) {
		tree.setRootNodeAllowedToCollapse(allowed);
	}

	public long getModificationID() {
		return modificationID.get();
	}

	private void showProgressPanel(boolean show) {
		if (show) {
			progressPanel = monitor;
			mainPanel.add(progressPanel, BorderLayout.SOUTH);
			progressPanel.invalidate();
		}
		else if (progressPanel != null) {
			mainPanel.remove(progressPanel);
			progressPanel = null;
		}
		validate();
		repaint();
	}

	private void showProgress(final int delay) {
		Runnable r = () -> {
			if (delay <= 0) {
				showProgressPanel(true);
			}
			else {
				showTimer = new Timer(delay, ev -> {
					if (isBusy()) {
						showProgressPanel(true);
						showTimer = null;
					}
				});
				showTimer.setInitialDelay(delay);
				showTimer.setRepeats(false);
				showTimer.start();
			}
		};
		SwingUtilities.invokeLater(r);
	}

	public boolean isBusy() {
		return worker.isBusy();
	}

	@Override
	public void setBusy(final boolean busy) {
		SystemUtilities.runSwingLater(() -> {
			if (busy) {
				showProgress(1000);
			}
			else {
				showProgressPanel(false);
			}
		});
	}

	public void refilter() {
		updateModelFilter();
	}

	public GTreeFilter getFilter() {
		return filter;
	}

	public boolean hasFilterText() {
		return hasFilterText;
	}

	public void clearFilter() {
		filterProvider.setFilterText("");
	}

	/**
	 * Used to run tree tasks.  This method is not meant for general clients of this tree, but
	 * rather for tasks to tell the tree to perform subtasks.
	 * 
	 * @param task the task to run
	 */
	public void runTask(GTreeTask task) {
		worker.schedule(task);
	}

	/**
	 * Used to run simple GTree tasks that can be expressed as a {@link MonitoredRunnable}
	 * (or a lambda taking a {@link TaskMonitor}).
	 * <p>
	 * @param runnableTask {@link TaskMonitor} to watch and update with progress.
	 */
	public void runTask(MonitoredRunnable runnableTask) {
		worker.schedule(new GTreeTask(this) {
			@Override
			public void run(TaskMonitor localMonitor) throws CancelledException {
				runnableTask.monitoredRun(localMonitor);
			}
		});
	}

	public synchronized void scheduleFilterTask(GTreeNode node) {
		if (!isFilteringEnabled()) {
			return;
		}
		if (nodesToBeFiltered.size() <= MAX_BUFFERED_FILTERED) {
			nodesToBeFiltered.add(node);
		}
		filterUpdateManager.update();
	}

	private synchronized void performNodeFiltering() {
		if (!isFilteringEnabled()) {
			return;
		}
		if (nodesToBeFiltered.isEmpty()) {
			return;
		}
		if (worker.isBusy()) {
			filterUpdateManager.updateLater();
			return;
		}
		if (nodesToBeFiltered.size() >= MAX_BUFFERED_FILTERED) {
			worker.schedule(new GTreeFilterTask(this, getRootNode(), filter));
		}
		else {
			for (GTreeNode node : nodesToBeFiltered) {
				worker.schedule(new GTreeFilterTask(this, node, filter));
			}
		}
		nodesToBeFiltered.clear();
	}

	public void runBulkTask(GTreeBulkTask task) {
		worker.schedule(task);
	}

	public boolean isEditing() {
		return tree.isEditing();
	}

	public void stopEditing() {
		tree.stopEditing();
	}

	public void setNodeEditable(GTreeNode child) {
		// for now only subclasses of GTree will set a node editable.
	}

	public boolean isFiltered() {
		return filter != null;
	}

	@Override
	public String toString() {
		GTreeRootNode rootNode = getRootNode();
		if (rootNode == null) {
			return "GTree - no root node";
		}
		return rootNode.toString();
	}

	@Override
	public String getToolTipText(MouseEvent event) {
		String text = super.getToolTipText(event);
		if (text != null) {
			return text;
		}
		return tree.getDefaultToolTipText(event);
	}

	public void clearSizeCache() {
		recurseClearSizeCache(getRootNode());
	}

	private void recurseClearSizeCache(GTreeNode node) {
		if (isExpanded(node.getTreePath())) {
			for (GTreeNode child : node.getChildren()) {
				recurseClearSizeCache(child);
			}
		}
		node.fireNodeChanged(node.getParent(), node);
	}

//==================================================================================================
// Inner Classes
//==================================================================================================

	class AutoScrollTree extends JTree implements Autoscroll {

		private AutoscrollAdapter scroller;
		private boolean paintLeafHandles = true;
		private int scrollableUnitIncrementOverride = -1;
		private boolean allowRootCollapse = true;

		public AutoScrollTree(TreeModel model) {
			super(model);
			scroller = new AutoscrollAdapter(this, 5);
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation,
				int direction) {
			if (scrollableUnitIncrementOverride != -1) {
				return scrollableUnitIncrementOverride;
			}
			return super.getScrollableUnitIncrement(visibleRect, orientation, direction);
		}

		public void setScrollableUnitIncrement(int increment) {
			this.scrollableUnitIncrementOverride = increment;
		}

		@Override
		public String getToolTipText(MouseEvent event) {
			// Use the GTree's method so clients can override the behavior; provide the
			// default method below so we they can get the default behavior when needed.
			return GTree.this.getToolTipText(event);
		}

		public String getDefaultToolTipText(MouseEvent event) {
			return super.getToolTipText(event);
		}

		@Override
		public void autoscroll(Point cursorLocn) {
			scroller.autoscroll(cursorLocn);
		}

		@Override
		public Insets getAutoscrollInsets() {
			return scroller.getAutoscrollInsets();
		}

		@Override
		public boolean isPathEditable(TreePath path) {
			return GTree.this.isPathEditable(path);
		}

		@Override
		public boolean hasBeenExpanded(TreePath path) {
			if (paintLeafHandles) {
				return super.hasBeenExpanded(path);
			}
			return true;
		}

		public void setPaintHandlesForLeafNodes(boolean enable) {
			this.paintLeafHandles = enable;
		}

		public void setRootNodeAllowedToCollapse(boolean allowed) {
			if (allowRootCollapse == allowed) {
				return;
			}
			allowRootCollapse = allowed;

			if (!allowed) {
				if (model != null && model.getRoot() != null) {
					runTask(new GTreeExpandNodeToDepthTask(GTree.this, getJTree(),
						model.getModelRoot(), 1));
				}
			}
		}

		public boolean isRootAllowedToCollapse() {
			return allowRootCollapse;
		}

		/**
		 * Need to override the addMouseListener method of the JTree to defer to the
		 *  delegate mouse listener.  The GTree uses a mouse listener delegate for itself
		 *  and the JTree it wraps.  When the delegate was installed, it moved all the existing mouse
		 *  listeners from the JTree to the delegate. Any additional listeners should also
		 *  be moved to the delegate.   Otherwise, some Ghidra components that use a convention/pattern
		 *  to avoid listener duplication by first removing a listener before adding it,
		 *  don't work and duplicates get added.
		 */
		@Override
		public synchronized void addMouseListener(MouseListener l) {
			if (mouseListenerDelegate == null) {
				super.addMouseListener(l);
			}
			else {
				mouseListenerDelegate.addMouseListener(l);
			}
		}

		/**
		 * Need to override the removeMouseListener method of the JTree to defer to the
		 *  delegate mouse listener.  The GTree uses a mouse listener delegate for itself
		 *  and the JTree it wraps.  When the delegate was installed, it moved all the existing mouse
		 *  listeners from the JTree to the delegate. All listener remove calls should also
		 *  be moved to the delegate.   Otherwise, some Ghidra components that use a convention/pattern
		 *  to avoid listener duplication by first removing a listener before adding it,
		 *  don't work and duplicates get added.
		 */
		@Override
		public synchronized void removeMouseListener(MouseListener l) {
			if (mouseListenerDelegate == null) {
				super.removeMouseListener(l);
			}
			else {
				mouseListenerDelegate.removeMouseListener(l);
			}
		}

		@Override
		public void removeSelectionPath(TreePath path) {
			// Called by the UI to add/remove selections--mark it as a user event.
			// Note: this code is based upon the fact that the BasicTreeUI calls this method on
			//       the tree when processing user clicks.  If another UI implementation were
			//       to call a different method, then we would have to re-think how we mark our
			//       events as user vs internally generated.
			GTreeSelectionModel gTreeSelectionModel = (GTreeSelectionModel) getSelectionModel();
			gTreeSelectionModel.userRemovedSelectionPath(path);
		}
	}

	/**
	 * Listens for changes to nodes in the tree to refilter and expand nodes as they are changed.
	 * We do this work here in the GTree, as opposed to doing it inside the GTreeNode, since the
	 * GTree can buffer requests and trigger the work to happen in tasks.  If the work was done
	 * in the nodes, then long running operations could block the Swing thread.
	 */
	private class FilteredExpansionListener implements TreeModelListener {

		/**
		 * We need this method to handle opening newly added tree nodes.  The GTreeNode will
		 * properly handle filtering for us in this case, but it does not expand nodes, as this
		 * is usually done in a separate task after the normal filtering process.
		 */
		@Override
		public void treeNodesInserted(TreeModelEvent e) {
			if (!hasFilterText) {
				return;
			}

			Object[] children = e.getChildren();
			for (Object child : children) {
				GTreeNode node = (GTreeNode) child;
				expandTree(node);
			}
		}

		/**
		 * We need this method to handle major tree changes that bypass the add and remove system
		 * of the GTreeNode.
		 */
		@Override
		public void treeStructureChanged(TreeModelEvent e) {
			if (!hasFilterText) {
				return;
			}

			Object lastPathComponent = e.getTreePath().getLastPathComponent();
			GTreeNode node = (GTreeNode) lastPathComponent;
			maybeTriggerUpdateForNode(node);
		}

		private void maybeTriggerUpdateForNode(GTreeNode node) {
			if ((node instanceof InProgressGTreeNode) ||
				(node instanceof InProgressGTreeRootNode)) {
				return;
			}

			// root structure changes imply that we are being rebuilt/refiltered and those tasks
			// are the result of an update, so there is nothing to do in that case
			if (node == model.getModelRoot()) {
				return;
			}

			updateModelFilter();
		}

		@Override
		public void treeNodesChanged(TreeModelEvent e) {
			// this is handled by the GTreeNode internally via adds and removes
		}

		@Override
		public void treeNodesRemoved(TreeModelEvent e) {
			// currently, the GTreeNode handles adds and removes on its own and updates the
			// model accordingly
		}
	}

	private class GTreeMouseListenerDelegate extends JTreeMouseListenerDelegate {
		private final GTree gTree;

		GTreeMouseListenerDelegate(JTree jTree, GTree gTree) {
			super(jTree);
			this.gTree = gTree;
		}

		/**
		 * Calling setSelectedPaths on GTree queues the selection for after
		 * any currently scheduled tasks. This method sets the selected path immediately
		 * and does not wait for for scheduled tasks.
		 * @param path the path to select.
		 */
		@Override
		protected void setSelectedPathNow(TreePath path) {
			GTreeSelectionModel selectionModel = (GTreeSelectionModel) gTree.getSelectionModel();
			selectionModel.setSelectionPaths(new TreePath[] { path }, USER_GENERATED);
		}
	}

//==================================================================================================
// Static Methods
//==================================================================================================

	private static String generateFilterPreferenceKey() {
		Throwable throwable = new Throwable();
		StackTraceElement[] stackTrace = throwable.getStackTrace();
		return getInceptionInformationFromTheFirstClassThatIsNotUs(stackTrace);
	}

	private static String getInceptionInformationFromTheFirstClassThatIsNotUs(
			StackTraceElement[] stackTrace) {

		// To find our creation point we can use a simple algorithm: find the name of our class,
		// which is in the first stack trace element and then keep walking backwards until that
		// name is not ours.
		//
		String myClassName = GTree.class.getName();
		int myClassNameStartIndex = -1;
		for (int i = 1; i < stackTrace.length; i++) {// start at 1, because we are the first item
			StackTraceElement stackTraceElement = stackTrace[i];
			String elementClassName = stackTraceElement.getClassName();
			if (myClassName.equals(elementClassName)) {
				myClassNameStartIndex = i;
				break;
			}
		}

		int creatorIndex = myClassNameStartIndex;
		for (int i = myClassNameStartIndex; i < stackTrace.length; i++) {
			StackTraceElement stackTraceElement = stackTrace[i];
			String elementClassName = stackTraceElement.getClassName();

			if (!myClassName.equals(elementClassName) &&
				!elementClassName.toLowerCase().endsWith("tree")) {
				creatorIndex = i;
				break;
			}
		}

		return stackTrace[creatorIndex].getClassName();
	}
}
