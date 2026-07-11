/*
 * Copyright 2023-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.ai.chat.client.observation;

import java.util.List;

import io.micrometer.observation.Observation;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.client.advisor.observation.DefaultAdvisorObservationConvention;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.observation.ChatModelObservationDocumentation;
import org.springframework.ai.chat.observation.DefaultChatModelObservationConvention;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.awaitility.Awaitility.await;

/**
 * Span hierarchy tests for {@link ChatClientMetadataPropagationObservationFilter}.
 *
 * @author Taewoong Kim
 */
class ChatClientMetadataPropagationSpanHierarchyTests {

	private static final String METADATA_KEY = "metadata.prompt.name";

	private static final String METADATA_VALUE = "checkout-assistant";

	private static final String RESPONSE_METADATA_KEY = "metadata.guardrail.result";

	private static final String RESPONSE_METADATA_VALUE = "passed";

	@Test
	void propagatesAdvisorMetadataToChatClientAndModelObservationsForCall() {
		TestObservationRegistry observationRegistry = observationRegistry();
		ChatClient chatClient = ChatClient
			.builder(new InstrumentedChatModel(observationRegistry), observationRegistry, null, null)
			.defaultAdvisors(new MetadataAdvisor())
			.build();

		chatClient.prompt("test").call().content();

		assertMetadataPropagated(observationRegistry);
	}

	@Test
	void propagatesAdvisorMetadataToChatClientAndModelObservationsForStream() {
		TestObservationRegistry observationRegistry = observationRegistry();
		ChatClient chatClient = ChatClient
			.builder(new InstrumentedChatModel(observationRegistry), observationRegistry, null, null)
			.defaultAdvisors(new MetadataAdvisor())
			.build();

		chatClient.prompt("test").stream().chatResponse().blockLast();

		await().untilAsserted(() -> assertMetadataPropagated(observationRegistry));
	}

	@Test
	void propagatesAdvisorRequestMetadataToFailedCallModelObservation() {
		TestObservationRegistry observationRegistry = observationRegistry();
		ChatClient chatClient = ChatClient
			.builder(InstrumentedChatModel.failing(observationRegistry), observationRegistry, null, null)
			.defaultAdvisors(new MetadataAdvisor())
			.build();

		assertThatIllegalStateException().isThrownBy(() -> chatClient.prompt("test").call().content())
			.withMessage("model invocation failed");

		TestObservationRegistryAssert.assertThat(observationRegistry)
			.hasObservationWithNameEqualTo(DefaultChatModelObservationConvention.DEFAULT_NAME)
			.that()
			.hasHighCardinalityKeyValue(METADATA_KEY, METADATA_VALUE)
			.doesNotHaveHighCardinalityKeyValueWithKey(RESPONSE_METADATA_KEY);
	}

	private static TestObservationRegistry observationRegistry() {
		TestObservationRegistry observationRegistry = TestObservationRegistry.create();
		observationRegistry.observationConfig()
			.observationFilter(new ChatClientMetadataPropagationObservationFilter(
					List.of(METADATA_KEY, RESPONSE_METADATA_KEY), 256));
		return observationRegistry;
	}

	private static void assertMetadataPropagated(TestObservationRegistry observationRegistry) {
		TestObservationRegistryAssert registryAssert = TestObservationRegistryAssert.assertThat(observationRegistry)
			.hasObservationWithNameEqualTo(DefaultChatClientObservationConvention.DEFAULT_NAME)
			.that()
			.backToTestObservationRegistry()
			.hasObservationWithNameEqualTo(DefaultAdvisorObservationConvention.DEFAULT_NAME)
			.that()
			.backToTestObservationRegistry()
			.hasObservationWithNameEqualTo(DefaultChatModelObservationConvention.DEFAULT_NAME)
			.that()
			.backToTestObservationRegistry();

		registryAssert
			.forAllObservationsWithNameEqualTo(DefaultChatClientObservationConvention.DEFAULT_NAME,
					observationContextAssert -> observationContextAssert
						.hasHighCardinalityKeyValue(METADATA_KEY, METADATA_VALUE)
						.hasHighCardinalityKeyValue(RESPONSE_METADATA_KEY, RESPONSE_METADATA_VALUE)
						.doesNotHaveHighCardinalityKeyValueWithKey("secret.token"))
			.forAllObservationsWithNameEqualTo(DefaultAdvisorObservationConvention.DEFAULT_NAME,
					observationContextAssert -> observationContextAssert
						.doesNotHaveHighCardinalityKeyValueWithKey(METADATA_KEY)
						.doesNotHaveHighCardinalityKeyValueWithKey(RESPONSE_METADATA_KEY)
						.doesNotHaveHighCardinalityKeyValueWithKey("secret.token"))
			.forAllObservationsWithNameEqualTo(DefaultChatModelObservationConvention.DEFAULT_NAME,
					observationContextAssert -> observationContextAssert
						.hasHighCardinalityKeyValue(METADATA_KEY, METADATA_VALUE)
						.doesNotHaveHighCardinalityKeyValueWithKey(RESPONSE_METADATA_KEY)
						.doesNotHaveHighCardinalityKeyValueWithKey("secret.token"));
	}

	private static ChatResponse response() {
		return ChatResponse.builder().generations(List.of(new Generation(new AssistantMessage("response")))).build();
	}

	private static final class MetadataAdvisor implements CallAdvisor, StreamAdvisor {

		@Override
		public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
			return withResponseMetadata(callAdvisorChain.nextCall(withMetadata(chatClientRequest)));
		}

		@Override
		public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest,
				StreamAdvisorChain streamAdvisorChain) {
			return streamAdvisorChain.nextStream(withMetadata(chatClientRequest))
				.map(MetadataAdvisor::withResponseMetadata);
		}

		private static ChatClientRequest withMetadata(ChatClientRequest chatClientRequest) {
			return chatClientRequest.mutate()
				.context(METADATA_KEY, METADATA_VALUE)
				.context("secret.token", "secret")
				.build();
		}

		private static ChatClientResponse withResponseMetadata(ChatClientResponse chatClientResponse) {
			return chatClientResponse.mutate().context(RESPONSE_METADATA_KEY, RESPONSE_METADATA_VALUE).build();
		}

		@Override
		public String getName() {
			return "metadataAdvisor";
		}

		@Override
		public int getOrder() {
			return Ordered.HIGHEST_PRECEDENCE;
		}

	}

	private static final class InstrumentedChatModel implements ChatModel {

		private final TestObservationRegistry observationRegistry;

		private final boolean failOnCall;

		InstrumentedChatModel(TestObservationRegistry observationRegistry) {
			this(observationRegistry, false);
		}

		private InstrumentedChatModel(TestObservationRegistry observationRegistry, boolean failOnCall) {
			this.observationRegistry = observationRegistry;
			this.failOnCall = failOnCall;
		}

		static InstrumentedChatModel failing(TestObservationRegistry observationRegistry) {
			return new InstrumentedChatModel(observationRegistry, true);
		}

		@Override
		public ChatResponse call(Prompt prompt) {
			ChatModelObservationContext observationContext = ChatModelObservationContext.builder()
				.prompt(prompt)
				.provider("test")
				.build();
			return ChatModelObservationDocumentation.CHAT_MODEL_OPERATION
				.observation(new DefaultChatModelObservationConvention(), new DefaultChatModelObservationConvention(),
						() -> observationContext, this.observationRegistry)
				.observe(() -> {
					if (this.failOnCall) {
						throw new IllegalStateException("model invocation failed");
					}
					ChatResponse response = response();
					observationContext.setResponse(response);
					return response;
				});
		}

		@Override
		public Flux<ChatResponse> stream(Prompt prompt) {
			return Flux.deferContextual(contextView -> {
				ChatModelObservationContext observationContext = ChatModelObservationContext.builder()
					.prompt(prompt)
					.provider("test")
					.streaming(true)
					.build();
				Observation observation = ChatModelObservationDocumentation.CHAT_MODEL_OPERATION.observation(
						new DefaultChatModelObservationConvention(), new DefaultChatModelObservationConvention(),
						() -> observationContext, this.observationRegistry);
				Observation parentObservation = contextView.getOrDefault(ObservationThreadLocalAccessor.KEY, null);
				observation.parentObservation(parentObservation);
				observation.start();

				ChatResponse response = response();
				observationContext.setResponse(response);

				return Flux.just(response)
					.doOnError(observation::error)
					.doFinally(signalType -> observation.stop())
					.contextWrite(context -> context.put(ObservationThreadLocalAccessor.KEY, observation));
			});
		}

	}

}
