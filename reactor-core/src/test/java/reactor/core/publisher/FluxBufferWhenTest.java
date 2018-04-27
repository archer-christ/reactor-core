/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.core.publisher;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Level;

import org.assertj.core.api.Condition;
import org.junit.Assert;
import org.junit.Test;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Scannable;
import reactor.test.StepVerifier;
import reactor.test.publisher.TestPublisher;
import reactor.test.subscriber.AssertSubscriber;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.concurrent.Queues;
import reactor.util.function.Tuple3;
import reactor.util.function.Tuples;

import static org.assertj.core.api.Assertions.assertThat;

public class FluxBufferWhenTest {

	private static final Logger LOGGER = Loggers.getLogger(FluxBufferWhenTest.class);

	//see https://github.com/reactor/reactor-core/issues/969
	@Test
	public void bufferedCanCompleteIfOpenNeverCompletesDropping() {
		//this test ensures that dropping buffers will complete if the source is exhausted before the open publisher finishes
		Mono<Integer> buffered = Flux.range(1, 200)
		                             .delayElements(Duration.ofMillis(25))
		                             .bufferWhen(Flux.interval(Duration.ZERO, Duration.ofMillis(200)),
				                             open -> Mono.delay(Duration.ofMillis(100)))
		                             .log(LOGGER, Level.FINE, false)
		                             .reduce(new HashSet<Integer>(), (set, buffer) -> { set.addAll(buffer); return set;})
		                             .map(HashSet::size);

		StepVerifier.create(buffered)
		            .assertNext(size -> assertThat(size).as("approximate size with drops").isBetween(80, 110))
		            .expectComplete()
		            .verify(Duration.ofSeconds(10));
	}

	//see https://github.com/reactor/reactor-core/issues/969
	@Test
	public void bufferedCanCompleteIfOpenNeverCompletesOverlapping() {
		//this test ensures that overlapping buffers will complete if the source is exhausted before the open publisher finishes
		Mono<Integer> buffered = Flux.range(1, 200)
		                             .delayElements(Duration.ofMillis(25))
		                             .bufferWhen(Flux.interval(Duration.ZERO, Duration.ofMillis(100)),
				                             open -> Mono.delay(Duration.ofMillis(200)))
		                             .log(LOGGER, Level.FINE, false)
		                             .reduce(new HashSet<Integer>(), (set, buffer) -> { set.addAll(buffer); return set;})
		                             .map(HashSet::size);

		StepVerifier.create(buffered)
		            .expectNext(200)
		            .expectComplete()
		            .verify(Duration.ofSeconds(10));
	}

	//see https://github.com/reactor/reactor-core/issues/969
	@Test
	public void timedOutBuffersDontLeak() throws InterruptedException {
		LongAdder created = new LongAdder();
		LongAdder finalized = new LongAdder();
		class Wrapper {

			final int i;

			Wrapper(int i) {
				created.increment();
				this.i = i;
			}

			@Override
			public String toString() {
				return "{i=" + i + '}';
			}

			@Override
			protected void finalize() {
				finalized.increment();
			}
		}

		final CountDownLatch latch = new CountDownLatch(1);
		final FluxProcessorSink<Wrapper> processor = Processors.unicast();

		Flux<Integer> emitter = Flux.range(1, 200)
		                            .delayElements(Duration.ofMillis(25))
		                            .doOnNext(i -> processor.next(new Wrapper(i)))
		                            .doOnError(processor::error)
		                            .doOnComplete(processor::complete);

		Mono<List<Tuple3<Long, String, Long>>> buffers =
				processor.asFlux()
				         .buffer(Duration.ofMillis(500), Duration.ofMillis(250))
				         .filter(b -> b.size() > 0)
				         .index()
				         .doOnNext(it -> System.gc())
				         //index, bounds of buffer, finalized
				         .map(t2 -> Tuples.of(t2.getT1(),
						         String.format("from %s to %s", t2.getT2().get(0),
								         t2.getT2().get(t2.getT2().size() - 1)),
						         finalized.longValue()))
				         .doOnNext(v -> LOGGER.debug(v.toString()))
				         .doOnComplete(latch::countDown)
				         .collectList();

		emitter.subscribe();
		List<Tuple3<Long, String, Long>> finalizeStats = buffers.block(Duration.ofSeconds(30));

		Condition<? super Tuple3<Long, String, Long>> hasFinalized = new Condition<>(
				t3 -> t3.getT3() > 0, "has finalized");

		//at least 5 intermediate finalize
		assertThat(finalizeStats).areAtLeast(5, hasFinalized);

		assertThat(latch.await(1, TimeUnit.SECONDS)).as("buffers already blocked").isTrue();
		LOGGER.debug("final GC");
		System.gc();
		Thread.sleep(500);

		assertThat(finalized.longValue())
				.as("final GC collects all")
				.isEqualTo(created.longValue());
	}

	@Test
	public void normal() {
		AssertSubscriber<List<Integer>> ts = AssertSubscriber.create();

		FluxProcessorSink<Integer> sp1 = Processors.direct();
		FluxProcessorSink<Integer> sp2 = Processors.direct();
		FluxProcessorSink<Integer> sp3 = Processors.direct();
		FluxProcessorSink<Integer> sp4 = Processors.direct();

		sp1.asFlux()
		   .bufferWhen(sp2.asFlux(), v -> v == 1 ? sp3.asFlux() : sp4.asFlux())
		   .subscribe(ts);

		ts.assertNoValues()
		  .assertNoError()
		  .assertNotComplete();

		sp1.next(1);

		ts.assertNoValues()
		  .assertNoError()
		  .assertNotComplete();

		sp2.next(1);

		Assert.assertTrue("sp3 has no subscribers?", sp3.hasDownstreams());

		sp1.next(2);
		sp1.next(3);
		sp1.next(4);

		sp3.complete();

		ts.assertValues(Arrays.asList(2, 3, 4))
		  .assertNoError()
		  .assertNotComplete();

		sp1.next(5);

		sp2.next(2);

		Assert.assertTrue("sp4 has no subscribers?", sp4.hasDownstreams());

		sp1.next(6);

		sp4.complete();

		ts.assertValues(Arrays.asList(2, 3, 4), Collections.singletonList(6))
		  .assertNoError()
		  .assertNotComplete();

		sp1.complete();

		ts.assertValues(Arrays.asList(2, 3, 4), Collections.singletonList(6))
		  .assertNoError()
		  .assertComplete();
	}

	@Test
	public void startCompletes() {
		AssertSubscriber<List<Integer>> ts = AssertSubscriber.create();

		FluxProcessorSink<Integer> source = Processors.direct();
		FluxProcessorSink<Integer> open = Processors.direct();
		FluxProcessorSink<Integer> close = Processors.direct();

		source.asFlux()
		      .bufferWhen(open.asFlux(), v -> close.asFlux())
		      .subscribe(ts);

		ts.assertNoValues()
		  .assertNoError()
		  .assertNotComplete();

		source.next(1);

		ts.assertNoValues()
		  .assertNoError()
		  .assertNotComplete();

		open.next(1);
		open.complete();

		Assert.assertTrue("close has no subscribers?", close.hasDownstreams());

		source.next(2);
		source.next(3);
		source.next(4);

		close.complete();

		ts.assertValues(Arrays.asList(2, 3, 4))
		  .assertNoError()
		  .assertComplete();

//		Assert.assertFalse("source has subscribers?", source.hasDownstreams()); //FIXME
		Assert.assertFalse("open has subscribers?", open.hasDownstreams());
		Assert.assertFalse("close has subscribers?", close.hasDownstreams());

	}


	@Test
	public void bufferWillAcumulateMultipleListsOfValuesOverlap() {
		//given: "a source and a collected flux"
		FluxProcessorSink<Integer> numbers = Processors.emitter();
		FluxProcessorSink<Integer> bucketOpening = Processors.emitter();

		//"overlapping buffers"
		FluxProcessorSink<Integer> boundaryFlux = Processors.emitter();

		MonoProcessor<List<List<Integer>>> res = numbers.asFlux()
		                                                .bufferWhen(bucketOpening.asFlux(), u -> boundaryFlux.asFlux())
		                                                .buffer()
		                                                .publishNext()
		                                                .toProcessor();
		res.subscribe();

		numbers.next(1);
		numbers.next(2);
		bucketOpening.next(1);
		numbers.next(3);
		bucketOpening.next(1);
		numbers.next(5);
		boundaryFlux.next(1);
		bucketOpening.next(1);
		boundaryFlux.complete();
		numbers.complete();

		//"the collected overlapping lists are available"
		assertThat(res.block()).containsExactly(Arrays.asList(3, 5),
				Collections.singletonList(5), Collections.emptyList());
	}

	Flux<List<Integer>> scenario_bufferWillSubdivideAnInputFluxOverlapTime() {
		return Flux.just(1, 2, 3, 4, 5, 6, 7, 8)
		           .delayElements(Duration.ofMillis(99))
		           .buffer(Duration.ofMillis(300), Duration.ofMillis(200));
	}

	@Test
	public void bufferWillSubdivideAnInputFluxOverlapTime() {
		StepVerifier.withVirtualTime(this::scenario_bufferWillSubdivideAnInputFluxOverlapTime)
		            .thenAwait(Duration.ofSeconds(10))
		            .assertNext(t -> assertThat(t).containsExactly(1, 2, 3))
		            .assertNext(t -> assertThat(t).containsExactly(3, 4, 5))
		            .assertNext(t -> assertThat(t).containsExactly(5, 6, 7))
		            .assertNext(t -> assertThat(t).containsExactly(7, 8))
		            .verifyComplete();
	}

	Flux<List<Integer>> scenario_bufferWillSubdivideAnInputFluxOverlapTime2() {
		return Flux.just(1, 2, 3, 4, 5, 6, 7, 8)
		           .delayElements(Duration.ofMillis(99))
		           .buffer(Duration.ofMillis(300L), Duration.ofMillis(200L));
	}

	@Test
	public void bufferWillSubdivideAnInputFluxOverlapTime2() {
		StepVerifier.withVirtualTime(this::scenario_bufferWillSubdivideAnInputFluxOverlapTime2)
		            .thenAwait(Duration.ofSeconds(10))
		            .assertNext(t -> assertThat(t).containsExactly(1, 2, 3))
		            .assertNext(t -> assertThat(t).containsExactly(3, 4, 5))
		            .assertNext(t -> assertThat(t).containsExactly(5, 6, 7))
		            .assertNext(t -> assertThat(t).containsExactly(7, 8))
		            .verifyComplete();
	}

	Flux<List<Integer>> scenario_bufferWillSubdivideAnInputFluxSameTime() {
		return Flux.just(1, 2, 3, 4, 5, 6, 7, 8)
		           .delayElements(Duration.ofMillis(99))
		           .buffer(Duration.ofMillis(300L), Duration.ofMillis(300L));
	}

	@Test
	public void bufferWillSubdivideAnInputFluxSameTime() {
		StepVerifier.withVirtualTime(this::scenario_bufferWillSubdivideAnInputFluxSameTime)
		            .thenAwait(Duration.ofSeconds(10))
		            .assertNext(t -> assertThat(t).containsExactly(1, 2, 3))
		            .assertNext(t -> assertThat(t).containsExactly(4, 5, 6))
		            .assertNext(t -> assertThat(t).containsExactly(7, 8))
		            .verifyComplete();
	}

	Flux<List<Integer>> scenario_bufferWillSubdivideAnInputFluxGapTime() {
		return Flux.just(1, 2, 3, 4, 5, 6, 7, 8)
		           .delayElements(Duration.ofMillis(99))
		           .buffer(Duration.ofMillis(200), Duration.ofMillis(300));
	}

	@Test
	public void bufferWillSubdivideAnInputFluxGapTime() {
		StepVerifier.withVirtualTime(this::scenario_bufferWillSubdivideAnInputFluxGapTime)
		            .thenAwait(Duration.ofSeconds(10))
		            .assertNext(t -> assertThat(t).containsExactly(1, 2))
		            .assertNext(t -> assertThat(t).containsExactly(4, 5))
		            .assertNext(t -> assertThat(t).containsExactly(7, 8))
		            .verifyComplete();
	}

	@Test
	public void scanStartEndMain() {
		CoreSubscriber<List<String>> actual = new LambdaSubscriber<>(null, e -> {}, null, null);

		FluxBufferWhen.BufferWhenMainSubscriber<String, Integer, Long, List<String>> test =
				new FluxBufferWhen.BufferWhenMainSubscriber<String, Integer, Long, List<String>>(
						actual, ArrayList::new, Queues.small(), Mono.just(1), u -> Mono.just(1L));
		Subscription parent = Operators.emptySubscription();
		test.onSubscribe(parent);
		test.request(100L);

		assertThat(test.scan(Scannable.Attr.PARENT)).isSameAs(parent);
		assertThat(test.scan(Scannable.Attr.TERMINATED)).isFalse();
		assertThat(test.scan(Scannable.Attr.CANCELLED)).isFalse();
		assertThat(test.scan(Scannable.Attr.PREFETCH)).isEqualTo(Integer.MAX_VALUE);
		assertThat(test.scan(Scannable.Attr.BUFFERED)).isEqualTo(0); //TODO
		assertThat(test.scan(Scannable.Attr.REQUESTED_FROM_DOWNSTREAM)).isEqualTo(100L);
		assertThat(test.scan(Scannable.Attr.ACTUAL)).isSameAs(actual);

		test.onError(new IllegalStateException("boom"));
		assertThat(test.scan(Scannable.Attr.TERMINATED)).isTrue();
	}

	@Test
	public void scanStartEndMainCancelled() {
		CoreSubscriber<List<String>> actual = new LambdaSubscriber<>(null, e -> {}, null, null);

		FluxBufferWhen.BufferWhenMainSubscriber<String, Integer, Long, List<String>> test =
				new FluxBufferWhen.BufferWhenMainSubscriber<String, Integer, Long, List<String>>(
				actual, ArrayList::new, Queues.small(), Mono.just(1), u -> Mono.just(1L));
		Subscription parent = Operators.emptySubscription();
		test.onSubscribe(parent);
		test.cancel();
		assertThat(test.scan(Scannable.Attr.CANCELLED)).isTrue();
	}

	@Test
	public void scanStartEndMainCompleted() {
		CoreSubscriber<List<String>> actual = new LambdaSubscriber<>(null, e -> {}, null, null);

		FluxBufferWhen.BufferWhenMainSubscriber<String, Integer, Long, List<String>> test =
				new FluxBufferWhen.BufferWhenMainSubscriber<String, Integer, Long, List<String>>(
				actual, ArrayList::new, Queues.small(), Mono.just(1), u -> Mono.just(1L));
		Subscription parent = Operators.emptySubscription();
		test.onSubscribe(parent);
		assertThat(test.scan(Scannable.Attr.TERMINATED)).isFalse();

		test.onComplete();
		assertThat(test.scan(Scannable.Attr.TERMINATED)).isTrue();
	}


	@Test
	public void scanWhenCloseSubscriber() {
		//noinspection ConstantConditions
		FluxBufferWhen.BufferWhenMainSubscriber<String, Integer, Long, List<String>> main =
				new FluxBufferWhen.BufferWhenMainSubscriber<>(null,
						ArrayList::new, Queues.small(), Mono.just(1),
						u -> Mono.just(1L));

		FluxBufferWhen.BufferWhenCloseSubscriber test = new FluxBufferWhen.BufferWhenCloseSubscriber<>(main, 5);

		assertThat(test.scan(Scannable.Attr.REQUESTED_FROM_DOWNSTREAM)).isEqualTo(Long.MAX_VALUE);

		Subscription parent = Operators.emptySubscription();
		test.onSubscribe(parent);

		assertThat(test.scan(Scannable.Attr.ACTUAL)).isSameAs(main);
		assertThat(test.scan(Scannable.Attr.PARENT)).isSameAs(parent);
		assertThat(test.scan(Scannable.Attr.CANCELLED)).isFalse();

		test.dispose();
		assertThat(test.isDisposed()).isTrue();
		assertThat(test.scan(Scannable.Attr.CANCELLED)).isTrue();
	}

	@Test
	public void scanWhenOpenSubscriber() {
		//noinspection ConstantConditions
		FluxBufferWhen.BufferWhenMainSubscriber<String, Integer, Long, List<String>> main = new FluxBufferWhen.BufferWhenMainSubscriber<>(
				null, ArrayList::new, Queues.small(), Mono.just(1), u -> Mono.just(1L));

		FluxBufferWhen.BufferWhenOpenSubscriber test = new FluxBufferWhen.BufferWhenOpenSubscriber<>(main);


		Subscription parent = Operators.emptySubscription();
		test.onSubscribe(parent);

		assertThat(test.scan(Scannable.Attr.REQUESTED_FROM_DOWNSTREAM)).isEqualTo(Long.MAX_VALUE);
		assertThat(test.scan(Scannable.Attr.ACTUAL)).isSameAs(main);
		assertThat(test.scan(Scannable.Attr.PARENT)).isSameAs(parent);
		assertThat(test.scan(Scannable.Attr.CANCELLED)).isFalse();

		test.dispose();
		assertThat(test.isDisposed()).isTrue();
		assertThat(test.scan(Scannable.Attr.CANCELLED)).isTrue();
	}

	@Test
	public void openCloseDisposedOnComplete() {
		TestPublisher<Integer> source = TestPublisher.create();
		TestPublisher<Integer> open = TestPublisher.create();
		TestPublisher<Integer> close = TestPublisher.create();

		StepVerifier.create(source
				.flux()
				.bufferWhen(open, o -> close))
		            .then(() -> {
			            source.assertSubscribers();
			            open.assertSubscribers();
			            close.assertNoSubscribers();
			            open.next(1);
		            })
		            .then(() -> {
						open.assertSubscribers();
						close.assertSubscribers();
						source.complete();
		            })
		            .expectNextMatches(List::isEmpty)
		            .verifyComplete();

		open.assertNoSubscribers();
		close.assertNoSubscribers();
	}

	@Test
	public void openCloseMainError() {
		StepVerifier.create(Flux.error(new IllegalStateException("boom"))
				.bufferWhen(Flux.never(), a -> Flux.never())
		)
		            .verifyErrorMessage("boom");
	}

	@Test
	public void openCloseBadSource() {
		TestPublisher<Object> badSource =
				TestPublisher.createNoncompliant(TestPublisher.Violation.CLEANUP_ON_TERMINATE);
		StepVerifier.create(badSource.flux()
				.bufferWhen(Flux.never(), a -> Flux.never()))
		            .then(() -> {
		            	badSource.error(new IOException("ioboom"));
		            	badSource.complete();
		            	badSource.next(1);
		            	badSource.error(new IllegalStateException("boom"));
		            })
		            .expectErrorMessage("ioboom")
		            .verifyThenAssertThat()
		            .hasNotDroppedElements()
		            .hasDroppedErrorWithMessage("boom");
	}

	@Test
	public void openCloseOpenCompletes() {
		TestPublisher<Integer> source = TestPublisher.create();
		TestPublisher<Integer> open = TestPublisher.create();
		TestPublisher<Integer> close = TestPublisher.create();

		StepVerifier.create(source.flux()
		                          .bufferWhen(open, o -> close)
		)
		            .then(() -> {
		            	open.next(1);
		            	close.assertSubscribers();
		            })
		            .then(() -> {
		            	open.complete();
		            	source.assertSubscribers();
		            	close.assertSubscribers();
		            })
		            .then(() -> {
		            	close.complete();
		            	source.assertNoSubscribers();
		            })
		            .expectNextMatches(List::isEmpty)
		            .verifyComplete();
	}

	@Test
	public void openCloseOpenCompletesNoBuffers() {
		TestPublisher<Integer> source = TestPublisher.create();
		TestPublisher<Integer> open = TestPublisher.create();
		TestPublisher<Integer> close = TestPublisher.create();

		StepVerifier.create(source.flux()
		                          .bufferWhen(open, o -> close))
		            .then(() -> {
			            open.next(1);
			            close.assertSubscribers();
		            })
		            .then(() -> {
			            close.complete();
			            source.assertSubscribers();
			            open.assertSubscribers();
		            })
		            .then(() -> {
			            open.complete();
			            source.assertNoSubscribers();
		            })
		            .expectNextMatches(List::isEmpty)
		            .verifyComplete();
	}

	@Test
	public void openCloseTake() {
		TestPublisher<Integer> source = TestPublisher.create();
		TestPublisher<Integer> open = TestPublisher.create();
		TestPublisher<Integer> close = TestPublisher.create();

		StepVerifier.create(source.flux()
		                          .bufferWhen(open, o -> close)
		                          .take(1), 2)
		            .then(() -> {
			            open.next(1);
			            close.complete();
		            })
		            .then(() -> {
			            source.assertNoSubscribers();
			            open.assertNoSubscribers();
			            close.assertNoSubscribers();
		            })
		            .expectNextMatches(List::isEmpty)
		            .verifyComplete();
	}

	@Test
	public void openCloseLimit() {
		TestPublisher<Integer> source = TestPublisher.create();
		TestPublisher<Integer> open = TestPublisher.create();
		TestPublisher<Integer> close = TestPublisher.create();

		StepVerifier.create(source.flux()
		                          .bufferWhen(open, o -> close)
		                          .limitRequest(1))
		            .then(() -> {
			            open.next(1);
			            close.complete();
		            })
		            .then(() -> {
			            source.assertNoSubscribers();
			            open.assertNoSubscribers();
			            close.assertNoSubscribers();
		            })
		            .expectNextMatches(List::isEmpty)
		            .verifyComplete();
	}

	@Test
	public void openCloseEmptyBackpressure() {
		TestPublisher<Integer> source = TestPublisher.create();
		TestPublisher<Integer> open = TestPublisher.create();
		TestPublisher<Integer> close = TestPublisher.create();

		StepVerifier.create(source.flux()
				.bufferWhen(open, o -> close), 0)
		            .then(() -> {
		            	source.complete();
		            	open.assertNoSubscribers();
		            	close.assertNoSubscribers();
		            })
		            .verifyComplete();
//		ts.assertResult();
	}

	@Test
	public void openCloseErrorBackpressure() {
		TestPublisher<Integer> source = TestPublisher.create();
		TestPublisher<Integer> open = TestPublisher.create();
		TestPublisher<Integer> close = TestPublisher.create();

		StepVerifier.create(source.flux()
		                          .bufferWhen(open, o -> close), 0)
		            .then(() -> {
			            source.error(new IllegalStateException("boom"));

			            open.assertNoSubscribers();
			            close.assertNoSubscribers();
		            })
		            .verifyErrorMessage("boom");
	}

	@Test
	public void openCloseBadOpen() {
		TestPublisher<Object> badOpen = TestPublisher.createNoncompliant(TestPublisher.Violation.CLEANUP_ON_TERMINATE);

		StepVerifier.create(Flux.never()
		                        .bufferWhen(badOpen, o -> Flux.never()))
		            .then(() -> {
			            badOpen.error(new IOException("ioboom"));
			            badOpen.complete();
			            badOpen.next(1);
			            badOpen.error(new IllegalStateException("boom"));
		            })

		            .expectErrorMessage("ioboom")
		            .verifyThenAssertThat()
		            .hasNotDroppedElements()
		            .hasDroppedErrorWithMessage("boom");
	}

	@Test
	public void openCloseBadClose() {
		TestPublisher<Object> badClose = TestPublisher.createNoncompliant(TestPublisher.Violation.CLEANUP_ON_TERMINATE);

		StepVerifier.create(Flux.never()
		                        .bufferWhen(Flux.just(1).concatWith(Flux.never()), o -> badClose)
		)
		            .then(() -> {
		            	badClose.error(new IOException("ioboom"));
		            	badClose.complete();
		            	badClose.next(1);
		            	badClose.error(new IllegalStateException("boom"));
		            })
		            .expectErrorMessage("ioboom")
		            .verifyThenAssertThat()
		            .hasNotDroppedElements()
		            .hasDroppedErrorWithMessage("boom");
	}
}
