/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.rpc.registration;

import akka.dispatch.OnFailure;
import akka.dispatch.OnSuccess;

import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.runtime.rpc.RpcGateway;
import org.apache.flink.runtime.rpc.RpcService;

import org.slf4j.Logger;

import scala.concurrent.Future;
import scala.concurrent.Promise;
import scala.concurrent.impl.Promise.DefaultPromise;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.apache.flink.util.Preconditions.checkArgument;
import static org.apache.flink.util.Preconditions.checkNotNull;


/**
 * This utility class implements the basis of registering one component at another component,
 * for example registering the TaskExecutor at the ResourceManager.
 * This {@code RetryingRegistration} implements both the initial address resolution
 * and the retries-with-backoff strategy.
 * 
 * <p>The registration gives access to a future that is completed upon successful registration.
 * The registration can be canceled, for example when the target where it tries to register
 * at looses leader status.
 * 
 * @param <Gateway> The type of the gateway to connect to.
 * @param <Success> The type of the successful registration responses.
 */
public abstract class RetryingRegistration<Gateway extends RpcGateway, Success extends RegistrationResponse.Success> {

	// ------------------------------------------------------------------------
	//  default configuration values
	// ------------------------------------------------------------------------

	private static final long INITIAL_REGISTRATION_TIMEOUT_MILLIS = 100;

	private static final long MAX_REGISTRATION_TIMEOUT_MILLIS = 30000;

	private static final long ERROR_REGISTRATION_DELAY_MILLIS = 10000;

	private static final long REFUSED_REGISTRATION_DELAY_MILLIS = 30000;

	// ------------------------------------------------------------------------
	// Fields
	// ------------------------------------------------------------------------

	private final Logger log;

	private final RpcService rpcService;

	private final String targetName;

	private final Class<Gateway> targetType;

	private final String targetAddress;

	private final UUID leaderId;

	private final Promise<Tuple2<Gateway, Success>> completionPromise;

	private final long initialRegistrationTimeout;

	private final long maxRegistrationTimeout;

	private final long delayOnError;

	private final long delayOnRefusedRegistration;

	private volatile boolean canceled;

	// ------------------------------------------------------------------------

	public RetryingRegistration(
			Logger log,
			RpcService rpcService,
			String targetName,
			Class<Gateway> targetType,
			String targetAddress,
			UUID leaderId) {
		this(log, rpcService, targetName, targetType, targetAddress, leaderId,
				INITIAL_REGISTRATION_TIMEOUT_MILLIS, MAX_REGISTRATION_TIMEOUT_MILLIS,
				ERROR_REGISTRATION_DELAY_MILLIS, REFUSED_REGISTRATION_DELAY_MILLIS);
	}

	public RetryingRegistration(
			Logger log,
			RpcService rpcService,
			String targetName, 
			Class<Gateway> targetType,
			String targetAddress,
			UUID leaderId,
			long initialRegistrationTimeout,
			long maxRegistrationTimeout,
			long delayOnError,
			long delayOnRefusedRegistration) {

		checkArgument(initialRegistrationTimeout > 0, "initial registration timeout must be greater than zero");
		checkArgument(maxRegistrationTimeout > 0, "maximum registration timeout must be greater than zero");
		checkArgument(delayOnError >= 0, "delay on error must be non-negative");
		checkArgument(delayOnRefusedRegistration >= 0, "delay on refused registration must be non-negative");

		this.log = checkNotNull(log);
		this.rpcService = checkNotNull(rpcService);
		this.targetName = checkNotNull(targetName);
		this.targetType = checkNotNull(targetType);
		this.targetAddress = checkNotNull(targetAddress);
		this.leaderId = checkNotNull(leaderId);
		this.initialRegistrationTimeout = initialRegistrationTimeout;
		this.maxRegistrationTimeout = maxRegistrationTimeout;
		this.delayOnError = delayOnError;
		this.delayOnRefusedRegistration = delayOnRefusedRegistration;

		this.completionPromise = new DefaultPromise<>();
	}

	// ------------------------------------------------------------------------
	//  completion and cancellation
	// ------------------------------------------------------------------------

	public Future<Tuple2<Gateway, Success>> getFuture() {
		return completionPromise.future();
	}

	/**
	 * Cancels the registration procedure.
	 */
	public void cancel() {
		canceled = true;
	}

	/**
	 * Checks if the registration was canceled.
	 * @return True if the registration was canceled, false otherwise.
	 */
	public boolean isCanceled() {
		return canceled;
	}

	// ------------------------------------------------------------------------
	//  registration
	// ------------------------------------------------------------------------

	protected abstract Future<RegistrationResponse> invokeRegistration(
			Gateway gateway, UUID leaderId, long timeoutMillis) throws Exception;

	/**
	 * This method resolves the target address to a callable gateway and starts the
	 * registration after that.
	 */
	@SuppressWarnings("unchecked")
	public void startRegistration() {
		try {
			// trigger resolution of the resource manager address to a callable gateway
			Future<Gateway> resourceManagerFuture = rpcService.connect(targetAddress, targetType);
	
			// upon success, start the registration attempts
			resourceManagerFuture.onSuccess(new OnSuccess<Gateway>() {
				@Override
				public void onSuccess(Gateway result) {
					log.info("Resolved {} address, beginning registration", targetName);
					register(result, 1, initialRegistrationTimeout);
				}
			}, rpcService.getExecutionContext());
	
			// upon failure, retry, unless this is cancelled
			resourceManagerFuture.onFailure(new OnFailure() {
				@Override
				public void onFailure(Throwable failure) {
					if (!isCanceled()) {
						log.warn("Could not resolve {} address {}, retrying...", targetName, targetAddress);
						startRegistration();
					}
				}
			}, rpcService.getExecutionContext());
		}
		catch (Throwable t) {
			cancel();
			completionPromise.tryFailure(t);
		}
	}

	/**
	 * This method performs a registration attempt and triggers either a success notification or a retry,
	 * depending on the result.
	 */
	@SuppressWarnings("unchecked")
	private void register(final Gateway gateway, final int attempt, final long timeoutMillis) {
		// eager check for canceling to avoid some unnecessary work
		if (canceled) {
			return;
		}

		try {
			log.info("Registration at {} attempt {} (timeout={}ms)", targetName, attempt, timeoutMillis);
			Future<RegistrationResponse> registrationFuture = invokeRegistration(gateway, leaderId, timeoutMillis);
	
			// if the registration was successful, let the TaskExecutor know
			registrationFuture.onSuccess(new OnSuccess<RegistrationResponse>() {
				
				@Override
				public void onSuccess(RegistrationResponse result) throws Throwable {
					if (!isCanceled()) {
						if (result instanceof RegistrationResponse.Success) {
							// registration successful!
							Success success = (Success) result;
							completionPromise.success(new Tuple2<>(gateway, success));
						}
						else {
							// registration refused or unknown
							if (result instanceof RegistrationResponse.Decline) {
								RegistrationResponse.Decline decline = (RegistrationResponse.Decline) result;
								log.info("Registration at {} was declined: {}", targetName, decline.getReason());
							} else {
								log.error("Received unknown response to registration attempt: " + result);
							}

							log.info("Pausing and re-attempting registration in {} ms", delayOnRefusedRegistration);
							registerLater(gateway, 1, initialRegistrationTimeout, delayOnRefusedRegistration);
						}
					}
				}
			}, rpcService.getExecutionContext());
	
			// upon failure, retry
			registrationFuture.onFailure(new OnFailure() {
				@Override
				public void onFailure(Throwable failure) {
					if (!isCanceled()) {
						if (failure instanceof TimeoutException) {
							// we simply have not received a response in time. maybe the timeout was
							// very low (initial fast registration attempts), maybe the target endpoint is
							// currently down.
							if (log.isDebugEnabled()) {
								log.debug("Registration at {} ({}) attempt {} timed out after {} ms",
										targetName, targetAddress, attempt, timeoutMillis);
							}
	
							long newTimeoutMillis = Math.min(2 * timeoutMillis, maxRegistrationTimeout);
							register(gateway, attempt + 1, newTimeoutMillis);
						}
						else {
							// a serious failure occurred. we still should not give up, but keep trying
							log.error("Registration at " + targetName + " failed due to an error", failure);
							log.info("Pausing and re-attempting registration in {} ms", delayOnError);
	
							registerLater(gateway, 1, initialRegistrationTimeout, delayOnError);
						}
					}
				}
			}, rpcService.getExecutionContext());
		}
		catch (Throwable t) {
			cancel();
			completionPromise.tryFailure(t);
		}
	}

	private void registerLater(final Gateway gateway, final int attempt, final long timeoutMillis, long delay) {
		rpcService.scheduleRunnable(new Runnable() {
			@Override
			public void run() {
				register(gateway, attempt, timeoutMillis);
			}
		}, delay, TimeUnit.MILLISECONDS);
	}
}
