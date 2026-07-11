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

package org.springframework.ai.model.chat.client.autoconfigure;

import java.util.List;

import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the chat client builder.
 *
 * @author Christian Tzolov
 * @author Mark Pollack
 * @author Josh Long
 * @author Arjen Poutsma
 * @author Thomas Vitale
 * @author Jonatan Ivanov
 * @author Taewoong Kim
 * @since 1.0.0
 */
@ConfigurationProperties(ChatClientBuilderProperties.CONFIG_PREFIX)
public class ChatClientBuilderProperties {

	public static final String CONFIG_PREFIX = "spring.ai.chat.client";

	/**
	 * Enable chat client builder.
	 */
	private boolean enabled = true;

	private final Observations observations = new Observations();

	private final ToolCalling toolCalling = new ToolCalling();

	public Observations getObservations() {
		return this.observations;
	}

	public ToolCalling getToolCalling() {
		return this.toolCalling;
	}

	public boolean isEnabled() {
		return this.enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public static class ToolCalling {

		/**
		 * Whether to auto-register a {@link ToolCallingAdvisor} in the advisor chain when
		 * tools are present on a call. Set to {@code false} to disable automatic tool
		 * execution and handle tool calls manually (user-controlled tool execution).
		 */
		private boolean enabled = true;

		/**
		 * Order of the auto-registered {@link ToolCallingAdvisor} in the advisor chain.
		 * Controls which advisors are inside the recursive tool-call loop: only advisors
		 * with a higher order value (i.e. downstream in the request direction)
		 * participate in each tool-call iteration.
		 */
		private int advisorOrder = ToolCallingAdvisor.DEFAULT_ORDER;

		public boolean isEnabled() {
			return this.enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public int getAdvisorOrder() {
			return this.advisorOrder;
		}

		public void setAdvisorOrder(int advisorOrder) {
			this.advisorOrder = advisorOrder;
		}

	}

	public static class Observations {

		/**
		 * Whether to log the prompt content in the observations.
		 */
		private boolean logPrompt = false;

		/**
		 * Whether to log the completion content in the observations.
		 * @since 1.1.0
		 */
		private boolean logCompletion = false;

		private final MetadataPropagation metadataPropagation = new MetadataPropagation();

		public boolean isLogPrompt() {
			return this.logPrompt;
		}

		/**
		 * @return Whether logging completion data is enabled or not.
		 * @since 1.1.0
		 */
		public boolean isLogCompletion() {
			return this.logCompletion;
		}

		public void setLogPrompt(boolean logPrompt) {
			this.logPrompt = logPrompt;
		}

		/**
		 * @param logCompletion should completion data logging be enabled or not.
		 * @since 1.1.0
		 */
		public void setLogCompletion(boolean logCompletion) {
			this.logCompletion = logCompletion;
		}

		/**
		 * @return the metadata propagation configuration
		 * @since 2.0.1
		 */
		public MetadataPropagation getMetadataPropagation() {
			return this.metadataPropagation;
		}

	}

	/**
	 * Configuration properties for propagating chat client context metadata to
	 * observations.
	 *
	 * @since 2.0.1
	 */
	public static class MetadataPropagation {

		/**
		 * Whether to propagate selected chat client context entries to chat client and
		 * nested chat model observations.
		 */
		private boolean enabled = false;

		/**
		 * Chat client context keys to propagate. Keys must match exactly.
		 */
		private List<String> includeKeys = List.of();

		/**
		 * Maximum length of propagated context values. Longer values are truncated. Must
		 * be greater than zero.
		 */
		private int maxValueLength = 256;

		public boolean isEnabled() {
			return this.enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public List<String> getIncludeKeys() {
			return this.includeKeys;
		}

		public void setIncludeKeys(List<String> includeKeys) {
			this.includeKeys = includeKeys;
		}

		public int getMaxValueLength() {
			return this.maxValueLength;
		}

		public void setMaxValueLength(int maxValueLength) {
			this.maxValueLength = maxValueLength;
		}

	}

}
