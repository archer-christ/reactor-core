/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.core.publisher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Scannable;
import reactor.util.Logger;
import reactor.util.Loggers;

/** @author Simon Baslé */
final class FluxMetrics<T> extends FluxOperator<T, T> {

	private static final Logger LOGGER = Loggers.getLogger(FluxMetrics.class);

	final String    name;
	final List<Tag> tags;

	FluxMetrics(Flux<? extends T> flux) {
		super(flux);
		//resolve the tags and names at instantiation
		Scannable scannable = Scannable.from(flux);
		if (scannable.isScanAvailable()) {
			this.name = scannable.name();
			this.tags = scannable.tags()
			                     .map(tuple -> Tag.of(tuple.getT1(), tuple.getT2()))
			                     .collect(Collectors.toList());
		}
		else {
			LOGGER.warn("Attempting to activate metrics but the upstream is not Scannable. " +
					"You might want to use `name()` (and optionally `tags()`) right before `metrics()`");
			this.name = REACTOR_DEFAULT_NAME;
			this.tags = Collections.emptyList();
		}
	}

	@Override
	public void subscribe(CoreSubscriber<? super T> actual) {
		InnerOperator<T,T> metricsOperator;
		try {
			metricsOperator = new MicrometerMetricsSubscriber<>(actual, Metrics.globalRegistry,
					this.name, this.tags, false);
		}
		catch (Throwable e) {
			metricsOperator = new SimplisticMetricsSubscriber<>(actual);
		}
		source.subscribe(metricsOperator);
	}

	static final class SimplisticMetricsSubscriber<T> implements InnerOperator<T,T> {

		final CoreSubscriber<? super T> actual;

		boolean done;
		Subscription s;

		SimplisticMetricsSubscriber(CoreSubscriber<? super T> actual) {
			this.actual = actual;
		}

		@Override
		public CoreSubscriber<? super T> actual() {
			return actual;
		}

		@Override
		public void onNext(T t) {
			if (done) {
				Operators.onNextDropped(t, actual.currentContext());
				return;
			}
			//TODO
		}

		@Override
		public void onError(Throwable e) {
			if (done) {
				Operators.onErrorDropped(e, actual.currentContext());
				return;
			}
			done = true;
			//TODO
		}

		@Override
		public void onComplete() {
			if (done) {
				return;
			}
			done = true;
			//TODO
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (Operators.validate(this.s, s)) {
				this.s = s;
				actual.onSubscribe(this);
			}
		}

		@Override
		public void request(long l) {
			if (Operators.validate(l)) {
				s.request(l);
			}

			//TODO
		}

		@Override
		public void cancel() {
			s.cancel();
			//TODO
		}
	}

	static final class MicrometerMetricsSubscriber<T> implements InnerOperator<T,T> {

		final CoreSubscriber<? super T> actual;
		final MeterRegistry             registry;

		final Counter                   malformedSourceCounter;
		final Counter                   subscribedCounter;
		
		final List<Tag> commonTags;

		final AtomicLong requested;

		Timer.Sample subscribeToTerminateSample;
		Timer.Sample onNextIntervalSample;

		boolean done;
		Subscription s;

		final Timer onNextIntervalTimer;
		final Timer subscribeToCompleteTimer;
		final Timer subscribeToErrorTimer;
		final Timer subscribeToCancelTimer;

		MicrometerMetricsSubscriber(CoreSubscriber<? super T> actual,
				MeterRegistry registry,
				String sequenceName,
				List<Tag> sequenceTags,
				boolean monoSource) {
			this.actual = actual;
			this.registry = registry;

			this.commonTags = new ArrayList<>();
			commonTags.add(Tag.of(TAG_SEQUENCE_NAME, sequenceName));
			commonTags.add(Tag.of(TAG_SEQUENCE_TYPE, monoSource ? TAGVALUE_MONO : TAGVALUE_FLUX));
			commonTags.addAll(sequenceTags);

			this.subscribeToCompleteTimer = Timer
					.builder(METER_SUBSCRIBE_TO_TERMINATE)
					.tags(commonTags)
					.tag(TAG_TERMINATION_TYPE, TAGVALUE_ON_COMPLETE)
					.description("Times the duration elapsed between a subscription and the onComplete termination of the sequence")
					.register(registry);
			this.subscribeToErrorTimer = Timer
					.builder(METER_SUBSCRIBE_TO_TERMINATE)
					.tags(commonTags)
					.tag(TAG_TERMINATION_TYPE, TAGVALUE_ON_ERROR)
					.description("Times the duration elapsed between a subscription and the onError termination of the sequence")
					.register(registry);
			this.subscribeToCancelTimer = Timer
					.builder(METER_SUBSCRIBE_TO_TERMINATE)
					.tags(commonTags)
					.tag(TAG_TERMINATION_TYPE, TAGVALUE_CANCEL)
					.description("Times the duration elapsed between a subscription and the cancellation of the sequence")
					.register(registry);

			this.onNextIntervalTimer = Timer
					.builder(METER_ON_NEXT_DELAY)
					.tags(commonTags)
					.description("Measures delays between onNext signals (or between onSubscribe and first onNext)")
					.register(registry);

			//find the global meters
			this.subscribedCounter = registry.counter(METER_SUBSCRIBED, this.commonTags);
			this.malformedSourceCounter = registry.counter(METER_MALFORMED, this.commonTags);
			this.requested = registry.gauge(METER_BACKPRESSURE, this.commonTags, new AtomicLong());
		}

		@Override
		public CoreSubscriber<? super T> actual() {
			return actual;
		}

		@Override
		public void onNext(T t) {
			if (done) {
				this.malformedSourceCounter.increment();
				Operators.onNextDropped(t, actual.currentContext());
				return;
			}
			requested.decrementAndGet();

			//record the delay since previous onNext/onSubscribe. This also records the count.
			this.onNextIntervalSample.stop(onNextIntervalTimer);
			//reset the timer sample
			this.onNextIntervalSample = Timer.start(registry);

			actual.onNext(t);
		}

		@Override
		public void onError(Throwable e) {
			if (done) {
				this.malformedSourceCounter.increment();
				Operators.onErrorDropped(e, actual.currentContext());
				return;
			}
			done = true;
			this.onNextIntervalSample.stop(this.onNextIntervalTimer);
			this.subscribeToTerminateSample.stop(subscribeToErrorTimer);

			actual.onError(e);
		}

		@Override
		public void onComplete() {
			if (done) {
				this.malformedSourceCounter.increment();
				return;
			}
			done = true;
			this.onNextIntervalSample.stop(this.onNextIntervalTimer);
			this.subscribeToTerminateSample.stop(subscribeToCompleteTimer);

			actual.onComplete();
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (Operators.validate(this.s, s)) {
				this.subscribedCounter.increment(); //TODO decrement somehow on termination?
				this.subscribeToTerminateSample = Timer.start(registry);
				this.onNextIntervalSample = Timer.start(registry);

				this.s = s;
				actual.onSubscribe(this);

			}
		}

		@Override
		public void request(long l) {
			if (Operators.validate(l)) {
				//reproduces the Operators.addCap(AtomicLongFieldUpdater) on AtomicLong
				long r, u;
				for (;;) {
					r = requested.get();
					if (r == Long.MAX_VALUE) {
						break;
					}
					u = Operators.addCap(r, l);
					if (requested.compareAndSet(r, u)) {
						break;
					}
				}
				s.request(l);
			}
		}

		@Override
		public void cancel() {
			this.subscribeToTerminateSample.stop(subscribeToCancelTimer);
			
			s.cancel();
		}
	}

	static final String REACTOR_DEFAULT_NAME = "reactor";

	static final String METER_MALFORMED              = "reactor.malformedSource";
	static final String METER_SUBSCRIBED             = "reactor.subscribed";
	static final String METER_SUBSCRIBE_TO_TERMINATE = "reactor.subscribeToTerminate";
	static final String METER_ON_NEXT_DELAY          = "reactor.onNextDelay";
	static final String METER_BACKPRESSURE           = "reactor.backpressure";

	static final String TAG_TERMINATION_TYPE = "reactor.terminationType";
	static final String TAG_SEQUENCE_NAME    = "reactor.sequenceName";
	static final String TAG_SEQUENCE_TYPE    = "reactor.sequenceType";

	static final String TAGVALUE_ON_ERROR    = "onError";
	static final String TAGVALUE_ON_COMPLETE = "onComplete";
	static final String TAGVALUE_CANCEL      = "cancel";
	static final String TAGVALUE_FLUX        = "Flux";
	static final String TAGVALUE_MONO        = "Mono";
}