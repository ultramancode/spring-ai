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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.tck.TestObservationRegistry;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.observation.AdvisorObservationContext;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Unit tests for {@link ChatClientMetadataPropagationObservationFilter}.
 *
 * @author Taewoong Kim
 */
class ChatClientMetadataPropagationObservationFilterTests {

	@Test
	void whenNotSupportedObservationContextThenReturnOriginalContext() {
		var filter = new ChatClientMetadataPropagationObservationFilter(List.of("metadata.prompt.name"), 256);
		var expectedContext = new Observation.Context();

		var actualContext = filter.map(expectedContext);

		assertThat(actualContext).isSameAs(expectedContext);
		assertThat(actualContext.getHighCardinalityKeyValues()).isEmpty();
	}

	@Test
	void whenNoKeysThenNoMetadataIsPropagated() {
		var filter = new ChatClientMetadataPropagationObservationFilter(List.of(), 256);
		ChatClientObservationContext context = chatClientObservationContext(
				Map.of("metadata.prompt.name", "checkout-assistant"));

		filter.map(context);

		assertThat(context.getHighCardinalityKeyValues()).isEmpty();
	}

	@Test
	void advisorObservationIsNotEnriched() {
		var filter = new ChatClientMetadataPropagationObservationFilter(
				List.of("metadata.request", "metadata.response"), 256);
		AdvisorObservationContext context = advisorObservationContext(Map.of("metadata.request", "request"));
		context.setChatClientResponse(
				ChatClientResponse.builder().context(Map.of("metadata.response", "response")).build());

		filter.map(context);

		assertThat(context.getHighCardinalityKeyValues()).isEmpty();
	}

	@Test
	void includedKeysMustMatchExactly() {
		var filter = new ChatClientMetadataPropagationObservationFilter(List.of("metadata."), 256);
		ChatClientObservationContext context = chatClientObservationContext(
				Map.of("metadata.prompt.name", "checkout-assistant"));

		filter.map(context);

		assertThat(context.getHighCardinalityKeyValues()).isEmpty();
	}

	@Test
	void chatClientObservationIncludesRequestAndResponseMetadata() {
		var filter = new ChatClientMetadataPropagationObservationFilter(
				List.of("metadata.request", "metadata.response"), 256);
		ChatClientObservationContext context = ChatClientObservationContext.builder()
			.request(ChatClientRequest.builder()
				.prompt(new Prompt("test"))
				.context(Map.of("metadata.request", "request"))
				.build())
			.build();
		context.setResponse(ChatClientResponse.builder()
			.context(Map.of("metadata.response", "response", "secret.token", "value"))
			.build());

		filter.map(context);

		assertThat(context.getHighCardinalityKeyValues()).contains(KeyValue.of("metadata.request", "request"),
				KeyValue.of("metadata.response", "response"));
		assertThat(context.getHighCardinalityKeyValues()).doesNotContain(KeyValue.of("secret.token", "value"));
	}

	@Test
	void responseMetadataOverridesRequestMetadata() {
		var filter = new ChatClientMetadataPropagationObservationFilter(List.of("metadata.prompt.name"), 256);
		ChatClientObservationContext context = ChatClientObservationContext.builder()
			.request(ChatClientRequest.builder()
				.prompt(new Prompt("test"))
				.context(Map.of("metadata.prompt.name", "request-version"))
				.build())
			.build();
		context.setResponse(
				ChatClientResponse.builder().context(Map.of("metadata.prompt.name", "response-version")).build());

		filter.map(context);

		assertThat(context.getHighCardinalityKeyValues())
			.containsExactly(KeyValue.of("metadata.prompt.name", "response-version"));
	}

	@Test
	void nullResponseMetadataDoesNotOverrideRequestMetadata() {
		var filter = new ChatClientMetadataPropagationObservationFilter(List.of("metadata.prompt.name"), 256);
		ChatClientObservationContext context = ChatClientObservationContext.builder()
			.request(ChatClientRequest.builder()
				.prompt(new Prompt("test"))
				.context(Map.of("metadata.prompt.name", "request-version"))
				.build())
			.build();
		Map<String, Object> responseMetadata = new LinkedHashMap<>();
		responseMetadata.put("metadata.prompt.name", null);
		context.setResponse(ChatClientResponse.builder().context(responseMetadata).build());

		filter.map(context);

		assertThat(context.getHighCardinalityKeyValues())
			.containsExactly(KeyValue.of("metadata.prompt.name", "request-version"));
	}

	@Test
	void existingHighCardinalityObservationKeyTakesPrecedence() {
		var filter = new ChatClientMetadataPropagationObservationFilter(List.of("metadata.prompt.name"), 256);
		ChatClientObservationContext context = chatClientObservationContext(
				Map.of("metadata.prompt.name", "context-value"));
		context.addHighCardinalityKeyValue(KeyValue.of("metadata.prompt.name", "observation-value"));

		filter.map(context);

		assertThat(context.getHighCardinalityKeyValues())
			.containsExactly(KeyValue.of("metadata.prompt.name", "observation-value"));
	}

	@Test
	void existingLowCardinalityObservationKeyTakesPrecedence() {
		var filter = new ChatClientMetadataPropagationObservationFilter(List.of("metadata.prompt.name"), 256);
		ChatClientObservationContext context = chatClientObservationContext(
				Map.of("metadata.prompt.name", "context-value"));
		context.addLowCardinalityKeyValue(KeyValue.of("metadata.prompt.name", "observation-value"));

		filter.map(context);

		assertThat(context.getLowCardinalityKeyValues())
			.containsExactly(KeyValue.of("metadata.prompt.name", "observation-value"));
		assertThat(context.getHighCardinalityKeyValues()).isEmpty();
	}

	@Test
	void whenRequestMetadataContainsNullValuesThenIgnoreThem() {
		var filter = new ChatClientMetadataPropagationObservationFilter(
				List.of("metadata.prompt.name", "metadata.empty"), 256);
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("metadata.prompt.name", "checkout-assistant");
		metadata.put("metadata.empty", null);
		ChatClientObservationContext context = chatClientObservationContext(metadata);

		filter.map(context);

		assertThat(context.getHighCardinalityKeyValues())
			.containsExactly(KeyValue.of("metadata.prompt.name", "checkout-assistant"));
	}

	@Test
	void metadataValuesAreStringifiedAndLimited() {
		var filter = new ChatClientMetadataPropagationObservationFilter(
				List.of("metadata.count", "metadata.long-value", "metadata.boolean"), 5);
		ChatClientObservationContext context = chatClientObservationContext(
				Map.of("metadata.count", 12345, "metadata.long-value", "abcdef", "metadata.boolean", true));

		filter.map(context);

		assertThat(context.getHighCardinalityKeyValues()).contains(KeyValue.of("metadata.count", "12345"),
				KeyValue.of("metadata.long-value", "abcde"), KeyValue.of("metadata.boolean", "true"));
	}

	@Test
	void chatModelObservationCopiesOnlyRequestMetadataFromParentAdvisorObservation() {
		var filter = new ChatClientMetadataPropagationObservationFilter(
				List.of("metadata.request", "metadata.response"), 256);
		ChatModelObservationContext context = chatModelObservationContext();
		AdvisorObservationContext advisorContext = advisorObservationContext(Map.of("metadata.request", "request"));
		advisorContext.setChatClientResponse(
				ChatClientResponse.builder().context(Map.of("metadata.response", "response")).build());
		context.setParentObservation(advisorObservation(advisorContext));

		filter.map(context);

		assertThat(context.getHighCardinalityKeyValues()).containsExactly(KeyValue.of("metadata.request", "request"));
	}

	@Test
	void whenFilterAppliedMultipleTimesThenMetadataIsNotDuplicated() {
		var filter = new ChatClientMetadataPropagationObservationFilter(List.of("metadata.prompt.name"), 256);
		ChatClientObservationContext context = chatClientObservationContext(
				Map.of("metadata.prompt.name", "checkout-assistant"));

		filter.map(context);
		filter.map(context);

		assertThat(context.getHighCardinalityKeyValues()
			.stream()
			.filter(keyValue -> keyValue.getKey().equals("metadata.prompt.name"))
			.count()).isEqualTo(1);
	}

	@Test
	void whenMaxValueLengthIsNotPositiveThenThrow() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new ChatClientMetadataPropagationObservationFilter(List.of("metadata.prompt.name"), 0))
			.withMessage("maxValueLength must be greater than 0");
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new ChatClientMetadataPropagationObservationFilter(List.of("metadata.prompt.name"), -1))
			.withMessage("maxValueLength must be greater than 0");
	}

	private static Observation advisorObservation(AdvisorObservationContext context) {
		return Observation.createNotStarted("spring.ai.advisor", () -> context, TestObservationRegistry.create());
	}

	private static AdvisorObservationContext advisorObservationContext(Map<String, ?> context) {
		return AdvisorObservationContext.builder()
			.advisorName("testAdvisor")
			.chatClientRequest(ChatClientRequest.builder().prompt(new Prompt("test")).context(context).build())
			.order(0)
			.build();
	}

	private static ChatClientObservationContext chatClientObservationContext(Map<String, ?> context) {
		return ChatClientObservationContext.builder()
			.request(ChatClientRequest.builder().prompt(new Prompt("test")).context(context).build())
			.build();
	}

	private static ChatModelObservationContext chatModelObservationContext() {
		return ChatModelObservationContext.builder().prompt(new Prompt("test")).provider("test").build();
	}

}
