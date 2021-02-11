/* 
 * MIT License
 * 
 * Copyright (c) 2020 Igram, d.o.o.
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
 
package rs.igram.kiribi.net;

import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.logging.Level;
import java.util.logging.Logger;

/** 
 * Provides execution methods.
 *
 *
 * @author Michael Sargent
 */
public class NetworkExecutor {
	private static final Logger LOGGER = Logger.getLogger(NetworkExecutor.class.getName());
	
	private final ForkJoinPool executor = new ForkJoinPool(
		10000,
        ForkJoinPool.defaultForkJoinWorkerThreadFactory,
        // suppress stack trace
        (t,e) -> {},
        true
    );
	private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);
	private final List<Action> actions = new ArrayList<>();
	
	/**
	 * Instantiates a new <code>NetworkExecutor</code> instance.
	 */
	public NetworkExecutor() {
		Runnable shutdown = () -> {
			scheduler.shutdownNow();
			try{
				Collections.sort(actions);
				new ArrayList<Action>(actions).forEach(a -> {
					a.runnable.run();
				});
				executor.shutdown();
				if(!executor.awaitTermination(1, TimeUnit.SECONDS)) {
					executor.shutdownNow();
					if(!executor.awaitTermination(2, TimeUnit.SECONDS))
						LOGGER.log(Level.FINE, "Kiribi pool failed to terminate");
				}
			}catch(InterruptedException e){
				executor.shutdownNow();
				Thread.currentThread().interrupt();
			}
		};
		Runtime.getRuntime().addShutdownHook(new Thread(shutdown));
	}
	
	/**
	 * Submits the provided task for execution.
	 *
	 * @param <T> The generic type of the task.
	 * @param task The task to run.
	 * @return Returns a future associated with the provided task.
	 */
	public final <T> Future<T> submit(Callable<T> task) {
		var t = ForkJoinTask.adapt(task);
		return executor.submit(t);
	}

	/**
	 * Submits the provided task for execution.
	 *
	 * @param task The task to run.
	 * @return Returns a future associated with the provided task.
	 */
	public final Future<?> submit(Runnable task) {
		var t = ForkJoinTask.adapt(task);
		return executor.submit(t);
	}
	
	/**
	 * Schedules the provided task for execution.
	 *
	 * @param <T> The generic type of the task.
	 * @param task The task to run.
	 * @param delay The time from now to delay execution.
	 * @param unit The time unit of the delay parameter.
	 * @return Returns a scheduled future associated with the provided task.
	 */
	public final <T> ScheduledFuture<T> schedule(Callable<T> task, long delay, TimeUnit unit) {
		return scheduler.schedule(task, delay, unit);
	}

	/**
	 * Schedules the provided task for execution.
	 *
	 * @param task The task to run.
	 * @param delay The time from now to delay execution.
	 * @param unit The time unit.
	 * @return Returns a scheduled future associated with the provided task.
	 */
	public final ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
		return scheduler.schedule(task, delay, unit);
	}
	
	/**
	 * Schedules the provided task for execution.
	 *
	 * @param task The task to run.
	 * @param initialDelay The time to delay first execution.
	 * @param period The period between successive executions.
	 * @param unit The time unit of the initialDelay and delay parameters.
	 * @return Returns a scheduled future associated with the provided task.
	 */
	public final ScheduledFuture<?> scheduleAtFixedRate(Runnable task, long initialDelay, long period, TimeUnit unit) {
		return scheduler.scheduleAtFixedRate(task, initialDelay, period, unit);
	}
	
	/**
	 * Schedules the provided task for execution.
	 *
	 * @param task The task to run.
	 * @param initialDelay The time to delay first execution.
	 * @param delay The delay between the termination ofone execution and the commencement of the next.
	 * @param unit The time unit of the delay parameter.
	 * @return Returns a scheduled future associated with the provided task.
	 */
	public final ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, long initialDelay, long delay, TimeUnit unit) {
		return scheduler.scheduleWithFixedDelay(task, initialDelay, delay, unit);
	}

	// ------------ sutdown ----------------------------------------------------
	/**
	 * Adds a task to run when this executor shuts down.
	 *
	 * @param priority The the priority of the task.
	 * @param runnable A task to run when this executor shuts down.
	 */
	public final void onShutdown(int priority, Runnable runnable) {
		actions.add(new Action(priority, runnable));	
	}
	
	// shutdown task
	private static class Action implements Comparable<Action> {
		final int priority;
		final Runnable runnable;
		
		Action(int priority, Runnable runnable) {
			this.priority = priority;
			this.runnable = runnable;
		}
		
		@Override
		public int compareTo(Action a){
			return priority - a.priority;
		}
	}
}
