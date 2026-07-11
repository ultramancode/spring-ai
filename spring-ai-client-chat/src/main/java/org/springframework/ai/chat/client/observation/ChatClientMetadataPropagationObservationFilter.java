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

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import org.jspecify.annotations.Nullable;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.observation.AdvisorObservationContext;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.util.Assert;

/**
 * An {@link ObservationFilter} that propagates explicitly selected context entries from
 * {@link ChatClientRequest} and {@link ChatClientResponse} to chat client observations.
 * Request entries available before the model invocation are also propagated to nested
 * chat model observations whose immediate parent has an
 * {@link AdvisorObservationContext}. Advisor observations are not enriched to avoid
 * duplicating the same metadata across the advisor chain. Keys are matched exactly, and
 * existing observation key-values are not replaced. Null values are ignored, and non-null
 * response values take precedence over request values for the same key.
 *
 * @author Taewoong Kim
 * @since 2.0.1
 */
public class ChatClientMetadataPropagationObservationFilter implements ObservationFilter {

	private final List<String> includeKeys;

	private final int maxValueLength;

	/**
	 * Create a metadata propagation filter.
	 * @param includeKeys exact context keys to propagate
	 * @param maxValueLength maximum length of each propagated value; must be greater than
	 * zero
	 */
	public ChatClientMetadataPropagationObservationFilter(List<String> includeKeys, int maxValueLength) {
		Assert.notNull(includeKeys, "includeKeys cannot be null");
		Assert.noNullElements(includeKeys, "includeKeys cannot contain null elements");
		Assert.isTrue(maxValueLength > 0, "maxValueLength must be greater than 0");

		this.includeKeys = List.copyOf(includeKeys);
		this.maxValueLength = maxValueLength;
	}

	@Override
	public Observation.Context map(Observation.Context context) {
		if (this.includeKeys.isEmpty()) {
			return context;
		}

		if (context instanceof ChatClientObservationContext chatClientObservationContext) {
			propagateMetadata(context, chatClientObservationContext.getRequest(),
					chatClientObservationContext.getResponse());
			return context;
		}

		if (context instanceof ChatModelObservationContext && context.getParentObservation() != null
				&& context.getParentObservation()
					.getContextView() instanceof AdvisorObservationContext advisorContext) {
			propagateMetadata(context, advisorContext.getChatClientRequest(), null);
		}

		return context;
	}

	private void propagateMetadata(Observation.Context observationContext, ChatClientRequest request,
			@Nullable ChatClientResponse response) {
		for (String key : this.includeKeys) {
			@Nullable Object value = response != null ? response.context().get(key) : null;
			if (value == null) {
				value = request.context().get(key);
			}
			if (value != null && !hasObservationKey(observationContext, key)) {
				observationContext.addHighCardinalityKeyValue(KeyValue.of(key, valueToString(value)));
			}
		}
	}

	private static boolean hasObservationKey(Observation.ContextView observationContext, String key) {
		return observationContext.getLowCardinalityKeyValue(key) != null
				|| observationContext.getHighCardinalityKeyValue(key) != null;
	}

	private String valueToString(Object value) {
		String stringValue = String.valueOf(value);
		if (stringValue.length() <= this.maxValueLength) {
			return stringValue;
		}
		return stringValue.substring(0, this.maxValueLength);
	}

}
