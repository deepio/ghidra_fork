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
package docking.widgets.table.threaded;

import java.util.ArrayList;
import java.util.List;

import javax.swing.SwingUtilities;

import docking.widgets.table.AddRemoveListItem;
import docking.widgets.table.TableSortingContext;
import generic.concurrent.ConcurrentListenerSet;
import ghidra.util.SystemUtilities;
import ghidra.util.task.*;

/** 
 * Manages the updating of ThreadTableModels.  As requests to load, sort, filter, add/remove item
 * in a ThreadedTableModel occur, this class schedules a TableUpdateJob to do the work.  It uses
 * a SwingUpdateManager to coalesce add/remove so that the table does not constantly update when
 * are large number of table changing events are incoming.
 */
class ThreadedTableModelUpdateMgr<T> {
	static final int TOO_MANY_ADD_REMOVES = 3000;
	public final static int DELAY = 1000;
	public final static int MAX_DELAY = 1000 * 60 * 20;

	private ThreadedTableModel<T, ?> model;
	private SwingUpdateManager updateManager;
	private TaskMonitor monitor;

	// weakly consistent iterator so that clients can remove listeners on notification
	private ConcurrentListenerSet<ThreadedTableModelListener> listeners =
		new ConcurrentListenerSet<>();

	private TableUpdateJob<T> pendingJob;
	private TableUpdateJob<T> currentJob;
	private Thread thread;

	private List<AddRemoveListItem<T>> addRemoveWaitList = new ArrayList<>();
	private final int maxAddRemoveCount;

	private Runnable notifyPending;
	private Runnable notifyUpdating;
	private Runnable notifyDone;
	private Runnable notifyCancelled;
	private Runnable threadRunnable = new ThreadRunnable();

	ThreadedTableModelUpdateMgr(ThreadedTableModel<T, ?> threadedTableModel, TaskMonitor monitor) {
		this.model = threadedTableModel;
		this.monitor = validateMonitor(monitor);
		this.maxAddRemoveCount = TOO_MANY_ADD_REMOVES;

		// see notes on DummyCancellableTaskMonitor...(in an ideal world this will be handled by
		// wrapping the given monitor with one that is usable by the Jobs of this class).
		SystemUtilities.assertTrue(this.monitor.isCancelEnabled(),
			"In order for this update manager to work correctly " +
				"the given task monitor must be cancel enabled (e.g., you cannot use " +
				"the TaskMonitorAdapter.DUMMY_MONITOR, as that is not cancelleable)");

		updateManager = new SwingUpdateManager(DELAY, MAX_DELAY, () -> processAddRemoveItems());

		notifyPending = () -> {
			for (ThreadedTableModelListener listener : listeners) {
				listener.loadPending();
			}
		};
		notifyUpdating = () -> {
			for (ThreadedTableModelListener listener : listeners) {
				listener.loadingStarted();
			}
		};
		notifyDone = () -> {
			for (ThreadedTableModelListener listener : listeners) {
				listener.loadingFinished(false);
			}
		};
		notifyCancelled = () -> {
			for (ThreadedTableModelListener listener : listeners) {
				listener.loadingFinished(true);
			}
		};
	}

	/**
	 * Ensures that the result of this call is a cancellable monitor.
	 * 
	 * <P>If the monitor used by the jobs of this class is not cancellable, then the jobs cannot
	 * properly move from state to state, since they are reused for new requests.
	 */
	private TaskMonitor validateMonitor(TaskMonitor clientMonitor) {

		if (clientMonitor != null && clientMonitor.isCancelEnabled()) {
			return clientMonitor;
		}

		return new DummyCancellableTaskMonitor();
	}

	Object getSynchronizingLock() {
		return updateManager;
	}

	/**
	 * Processes the accumulated list of add/remove items.
	 * Called when the swing update manager decides its time to run.
	 */
	private void processAddRemoveItems() {
		synchronized (updateManager) {
			if (addRemoveWaitList.isEmpty()) {
				return;
			}
			// too many requests for add/remove triggers a full reload for efficiency
			if (addRemoveWaitList.size() > maxAddRemoveCount) {
				reload();
				return;
			}
			pendingJob = new AddRemoveJob<>(model, addRemoveWaitList, monitor);
			addRemoveWaitList = new ArrayList<>();
			runJob();
		}
	}

	/**
	 * Called when there is work to be done.  It creates a thread if none is running to do the
	 * work that is built into the pending job.
	 */
	private void runJob() {
		synchronized (updateManager) {
			if (thread != null) {
				return; // if thread exists, it will handle any pending job.
			}
			if (pendingJob == null) {
				return; // nothing to do!
			}
			// Ok, we have to create a thread to process the pending job
			thread = new Thread(threadRunnable,
				"Threaded Table Model Update Manager: " + model.getName());

			thread.start();
			SwingUtilities.invokeLater(notifyUpdating);
		}
	}

	/**
	 * Warning!:  This cancels the current job, pending jobs and anything waiting in the update
	 *  manager.  This method is meant to be used outside of normal usage.  That is, it should
	 *  be used when you really have to cancel everything that is going on in order to restart
	 *  things.
	 */
	public void cancelAllJobs() {
		synchronized (updateManager) {
			updateManager.stop();
			if (currentJob != null) {
				currentJob.cancel();
			}
			pendingJob = null;
			addRemoveWaitList.clear();
		}
	}

	/**
	 * Called when the table data needs to be totally reloaded.  An example is when a undo or redo
	 * is performed.  It also is called if too many add/removes have been accumulated.
	 */
	void reload() {
		synchronized (updateManager) {
			cancelAllJobs();
			pendingJob = new LoadJob<>(model, monitor);
			runJob();
		}
	}

	void reloadSpecificData(List<T> data) {
		synchronized (updateManager) {
			cancelAllJobs();
			TableData<T> tableData = TableData.createFullDataset(data);
			pendingJob = new LoadSpecificDataJob<>(model, monitor, tableData);
			runJob();
		}
	}

	/**
	 * Tells this update manager that the table data needs to be resorted.  If a current job
	 * is running, it will attempt to add the sort work directly to the running job.  If the running
	 * job has not gotten to the sort phase yet, the new sort will replace the currently scheduled sort.
	 * If the current job is sorting or has already sorted, it will be interrupted and return to
	 * the sorted state.
	 * <p>
	 * If a pending job is already waiting, the sort will be added to the pending job.
	 * <p>
	 * If no jobs exists, a new job will be created to do the sort and runJob will be called to
	 * start a thread to do the work.
	 * 
	 * @param sortingContext the context used to sort the data
	 * @param forceSort True signals to re-sort the data (useful when the data changes and needs
	 *                  to be re-sorted.
	 */
	void sort(TableSortingContext<T> sortingContext, boolean forceSort) {
		synchronized (updateManager) {
			if (currentJob != null && pendingJob == null &&
				currentJob.sort(sortingContext, forceSort)) {
				return;
			}

			if (pendingJob != null) {
				pendingJob.sort(sortingContext, forceSort);
			}
			else {
				pendingJob = new SortJob<>(model, monitor, sortingContext, forceSort);
				runJob();
			}
		}
	}

	/**
	 * Tells this update manager that the table data needs to be re-filtered.  If a current job
	 * is running, it will attempt to add the filter work directly to the running job.  If the running
	 * job has not gotten to the filter phase yet, nothing needs to be done since the data will be
	 * re-filtered anyway
	 * If the current job is currently filtering or has already filtered, it will be 
	 * interrupted and return to the filter state.
	 * <p>
	 * If a pending job is already waiting, the filter job will be added to the pending job.
	 * <p>
	 * if no jobs exists, a new job will be created to do the filter and runJob will be called to
	 * start a thread to do the work.
	 */
	void filter() {
		synchronized (updateManager) {
			if (currentJob != null && pendingJob == null && currentJob.filter()) {
				return;
			}
			if (pendingJob != null) {
				pendingJob.filter();
			}
			else {
				pendingJob = new FilterJob<>(model, monitor);
				runJob();
			}
		}
	}

	/**
	 * Tells this update manager that a new row item has been added or removed.  Add/removes never
	 * affect any currently running job.  If a pending job exists, the add/remove will be added
	 * to the pending job.
	 * 
	 * if no pending jobs exists, the add/remove item will be added to a list to be processed later
	 * when the swing update manager's timer expires.
	 * @param item the add/remove item to process.
	 */
	void addRemove(AddRemoveListItem<T> item) {
		synchronized (updateManager) {
			if (pendingJob != null) {
				pendingJob.addRemove(item, maxAddRemoveCount);
				return;
			}

			if (addRemoveWaitList.size() > maxAddRemoveCount) {
				reload();
				return;
			}

			if (addRemoveWaitList.isEmpty() && thread == null) {
				SwingUtilities.invokeLater(notifyPending);
			}

			addRemoveWaitList.add(item);
			updateManager.update();
		}
	}

	/**
	 * Returns true if there is any scheduled work that has not been completed, including any
	 * deferred add/removes. 
	 * @return true if there is work to be done.
	 */
	boolean isBusy() {
		synchronized (updateManager) {
			return thread != null || pendingJob != null || updateManager.isBusy() ||
				!addRemoveWaitList.isEmpty();
		}
	}

	/**
	 * Sets the delay for the swing update manager.
	 * @param updateDelayMillis the new delay for the swing update manager.
	 */
	void setUpdateDelay(int updateDelayMillis, int maxUpdateDelayMillis) {
		updateManager.dispose();
		updateManager = new SwingUpdateManager(updateDelayMillis, maxUpdateDelayMillis,
			() -> processAddRemoveItems());
	}

	/**
	 * Sets the task monitor for this manager.
	 * @param monitor the new monitor to use.
	 */
	void setTaskMonitor(TaskMonitor monitor) {
		this.monitor = monitor;
	}

	TaskMonitor getTaskMonitor() {
		return monitor;
	}

	/**
	 * Sets the ThreadedTableListener.  Only one listener is supported, so setting a new listener
	 * will replace any existing listener.
	 * @param listener the new ThreadedTableListener to use.
	 */
	void addThreadedTableListener(ThreadedTableModelListener listener) {
		listeners.add(listener);
	}

	void removeThreadedTableListener(ThreadedTableModelListener listener) {
		listeners.remove(listener);
	}

	/**
	 * Disposes the updateManager resource.
	 */
	void dispose() {
		synchronized (updateManager) {
			listeners.clear();
			monitor.cancel();
			monitor = new PermantentlyCancelledMonitor();
			cancelAllJobs();
			updateManager.dispose();
		}
	}

	/**
	 * Kicks the swing update manager to immediately process any accumulated add/removes.
	 */
	public void updateNow() {
		updateManager.updateNow();
	}

	/**
	 * Transitions the pending job to the current job is a thread safe way.
	 * @return the new current job.
	 */
	private TableUpdateJob<T> getNextJob() {
		synchronized (updateManager) {
			currentJob = pendingJob;
			pendingJob = null;
			return currentJob;
		}
	}

	/**
	 * Called when the current jobs has been completed.  It notifies the listener, clears the
	 * thread variable, and checks if any add/removes are pending, in which case it set the state
	 * to work pending.
	 */
	private void jobDone() {
		synchronized (updateManager) {

			boolean isCancelled = monitor.isCancelled();
			if (isCancelled) {
				SwingUtilities.invokeLater(notifyCancelled);
			}
			else {
				SwingUtilities.invokeLater(notifyDone);
			}

			thread = null;
			if (!addRemoveWaitList.isEmpty()) {
				SwingUtilities.invokeLater(notifyPending);
			}
		}
	}

//==================================================================================================
// Inner Classes
//==================================================================================================	

	/**
	 * Runnable used be new threads to run scheduled jobs.
	 */
	class ThreadRunnable implements Runnable {
		@Override
		public void run() {
			TableUpdateJob<T> job = getNextJob();
			while (job != null) {
				monitor.clearCanceled();
				job.run();

				// useful for debug				
//				Msg.debug(this, "ran job: " + job);
				job = getNextJob();

			}
			jobDone();
		}
	}

	/** 
	 * A monitor that allows us to make sure that this update manager does not clear the cancel
	 * done in {@link ThreadedTableModelUpdateMgr#dispose()}.  This is useful if we want to never
	 * again perform any work, such as when we are disposed.
	 */
	private class PermantentlyCancelledMonitor extends TaskMonitorAdapter {
		public PermantentlyCancelledMonitor() {
			setCancelEnabled(true);
			cancel();
		}

		@Override
		public void clearCanceled() {
			// don't allow this
		}
	}

}
