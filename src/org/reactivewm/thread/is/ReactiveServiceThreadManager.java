package org.reactivewm.thread.is;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.reactivewm.controller.CancelController;
import org.reactivewm.controller.ControllerCallback;
import org.reactivewm.controller.ControllerManager;
import org.reactivewm.exception.FailfastException;
import org.reactivewm.exception.ThreadException;
import org.reactivewm.executor.ISThreadPoolExecutor;
import org.reactivewm.executor.ThreadExecutable;

import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.wm.app.b2b.server.Service;
import com.wm.app.b2b.server.ServiceThread;
import com.wm.app.b2b.server.Session;
import com.wm.data.IData;
import com.wm.lang.ns.NSName;

/**
 * Manager of ReactiveServiceThread offering utility services and executors
 * management
 * 
 * @author Teiva Harsanyi
 * 
 */
public class ReactiveServiceThreadManager {

	private static final Logger LOG = Logger
			.getLogger(ReactiveServiceThreadManager.class);
	private static ReactiveServiceThreadManager INSTANCE;
	private Map<String, ISThreadPoolExecutor> executors;
	private static final long SHUTDOWN_TIMEOUT = 1;
	private static final TimeUnit SHUTDOWN_TIMEUNIT = TimeUnit.MINUTES;

	private ReactiveServiceThreadManager() {
		executors = new ConcurrentHashMap<String, ISThreadPoolExecutor>();
	}

	public static ReactiveServiceThreadManager getInstance() {
		if (INSTANCE == null) {
			INSTANCE = new ReactiveServiceThreadManager();
		}

		return INSTANCE;
	}

	private ISThreadPoolExecutor getExecutor(String pool)
			throws ThreadException {
		if (isPoolExists(pool)) {
			return executors.get(pool);
		} else {
			throw new ThreadException(
					"Exception while retrieving a thread pool: " + pool
							+ " not defined");
		}
	}

	public ServiceThread createServiceThread(String service, IData input,
			int threadPriority, boolean interruptable) {
		return createServiceThread(service, input, Service.getSession(),
				threadPriority, interruptable);
	}

	public ServiceThread createServiceThread(String service, IData input,
			Session session, int threadPriority, boolean interruptable) {
		return new ReactiveServiceThread(NSName.create(service), session,
				input, threadPriority, interruptable);
	}

	public boolean isPoolExists(String pool) {
		return executors.containsKey(pool);
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	public void createPool(String pool, int poolSize, ThreadFactory factory,
			ThreadExecutable executable) {
		if (!executors.containsKey(pool)) {
			executors.put(pool, new ISThreadPoolExecutor(poolSize, poolSize,
					0L, TimeUnit.SECONDS, new PriorityBlockingQueue(poolSize,
							new ListenableFutureTaskComparator()), factory,
					executable));
		}
	}

	public String addControllerFailure(String pool,
			List<Future<IData>> futures, List<ServiceThread> serviceThreads)
			throws ThreadException {
		ControllerManager manager = ControllerManager.getInstance();
		String controller = manager.addController(serviceThreads, futures);

		ControllerCallback<IData> controllerCallback = new CancelController<IData>(
				controller);
		for (Future<IData> future : futures) {
			ListenableFuture<IData> listenable = (ListenableFuture<IData>) future;
			Futures.addCallback(listenable, controllerCallback);
		}

		return controller;
	}

	public ListenableFuture<IData> chain(String pool,
			ListenableFuture<IData> future, String service, IData input,
			Integer threadPriority, boolean merge, boolean interruptable) throws ThreadException {
		ISThreadPoolExecutor ex = getExecutor(pool);

		AsyncFunction<IData, IData> callback = new ReactiveAsyncFunction(ex,
				service, input, threadPriority, merge, Service.getSession()
						.getSessionID(), interruptable);

		return Futures.transform(future, callback);
	}

	public ListenableFuture<IData> chain(String pool,
			ListenableFuture<IData> future, String service, IData input,
			Integer threadPriority, boolean merge, boolean interruptable, String errService,
			IData errInput, Integer errThreadPriority, boolean errInterruptable) throws ThreadException {
		ISThreadPoolExecutor ex = getExecutor(pool);

		String session = Service.getSession().getSessionID();

		AsyncFunction<IData, IData> chain = new ReactiveAsyncFunction(ex,
				service, input, threadPriority, merge, session, interruptable);
		FutureCallback<IData> callback = new FailureCallback<IData>(ex,
				errService, errInput, errThreadPriority, session, errInterruptable);

		Futures.addCallback(future, callback);
		return Futures.transform(future, chain);
	}

	public ListenableFuture<IData> submit(String pool, String service,
			IData input, Integer threadPriority, boolean interruptable)
			throws ThreadException {
		return submit(
				pool,
				createServiceThread(service, input, threadPriority,
						interruptable));
	}

	public ListenableFuture<IData> submit(String pool,
			ServiceThread serviceThread) throws ThreadException {
		ISThreadPoolExecutor ex = getExecutor(pool);

		return (ListenableFuture<IData>) submit(ex, serviceThread);
	}

	@SuppressWarnings("unchecked")
	public ListenableFuture<IData> submit(ISThreadPoolExecutor executor,
			ServiceThread serviceThread) throws ThreadException {

		return (ListenableFuture<IData>) executor.submit(serviceThread, false);
	}

	@SuppressWarnings("unchecked")
	ListenableFuture<IData> submitController(ISThreadPoolExecutor executor,
			Runnable runnable) throws ThreadException {

		return (ListenableFuture<IData>) executor.submit(runnable, true);
	}

	public void changePoolSize(String pool, int poolSize)
			throws ThreadException {
		ISThreadPoolExecutor ex = getExecutor(pool);

		ex.setCorePoolSize(poolSize);
	}

	public void wait(List<Future<IData>> futures, long timeout,
			TimeUnit timeUnit, boolean failfast) throws ThreadException,
			TimeoutException, FailfastException {
		long max = System.currentTimeMillis()
				+ TimeUnit.MILLISECONDS.convert(timeout, timeUnit);

		if (futures == null || futures.size() == 0) {
			return;
		}

		for (Future<IData> future : futures) {
			long current = System.currentTimeMillis();
			if (current >= max) {
				throw new TimeoutException("Timeout exception");
			}

			try {
				Futures.get(future, max - current, TimeUnit.MILLISECONDS,
						ExecutionException.class);
			} catch (ExecutionException e) {
				if (e.getCause() instanceof TimeoutException) {
					for (Future<IData> f : futures) {
						ListenableFutureTask<IData> lf = (ListenableFutureTask<IData>) f;
						ReactiveServiceThread ast = (ReactiveServiceThread) lf
								.getRunnable();
						ast.cancel();
					}

					throw new TimeoutException("Timeout exception");
				} else {
					if (failfast) {
						throw new FailfastException(e.getCause().getMessage(),
								e.getCause());
					}
				}
			} catch (Exception e) {
				throw new ThreadException(e);
			}
		}
	}

	public void shutdown() {
		for (Map.Entry<String, ISThreadPoolExecutor> entry : executors
				.entrySet()) {
			try {
				closePool(entry.getKey(), SHUTDOWN_TIMEOUT, SHUTDOWN_TIMEUNIT);
			} catch (Exception e) {
				LOG.log(Level.ERROR, "Shutdown exception: " + e.getMessage());
			}
		}
	}

	public void closePool(String pool, Long timeout, TimeUnit timeUnit)
			throws ThreadException {

		ISThreadPoolExecutor ex = getExecutor(pool);

		ex.shutdown();

		try {
			if (!ex.awaitTermination(timeout, timeUnit)) {
				ex.shutdownNow();
			}
		} catch (InterruptedException e1) {
			throw new ThreadException("Shutdown interruption: "
					+ e1.getMessage());
		} finally {
			try {
				executors.remove(pool);
			} catch (Exception e) {

			}
		}
	}

	public String introspect() {
		StringBuilder sb = new StringBuilder();

		sb.append("{");
		int size = executors.size();
		int i = 0;
		for (Map.Entry<String, ISThreadPoolExecutor> entry : executors
				.entrySet()) {
			sb.append("\"").append(entry.getKey()).append("\":");

			ISThreadPoolExecutor ex = entry.getValue();
			if (ex == null) {
				sb.append("null");
			} else {
				ex.getActiveCount();
				ex.getCorePoolSize();
				sb.append("{\"corePoolSize\":").append(ex.getCorePoolSize())
						.append(",\"activeCount\":")
						.append(ex.getActiveCount()).append("}");
			}

			if (i++ != size - 1) {
				sb.append(",");
			}
		}
		sb.append("}");
		return sb.toString();
	}
}