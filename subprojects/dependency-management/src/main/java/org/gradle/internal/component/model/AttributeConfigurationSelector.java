/*
 * Copyright 2017 the original author or authors.
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

package org.gradle.internal.component.model;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.capabilities.Capability;
import org.gradle.api.internal.attributes.AttributesSchemaInternal;
import org.gradle.api.internal.attributes.ImmutableAttributes;
import org.gradle.internal.component.AmbiguousConfigurationSelectionException;
import org.gradle.internal.component.NoMatchingCapabilitiesException;
import org.gradle.internal.component.NoMatchingConfigurationSelectionException;

import java.util.Collection;
import java.util.List;

public abstract class AttributeConfigurationSelector {

    public static ConfigurationMetadata selectConfigurationUsingAttributeMatching(ImmutableAttributes consumerAttributes, Collection<? extends Capability> explicitRequestedCapabilities, ComponentResolveMetadata targetComponent, AttributesSchemaInternal consumerSchema) {
        Optional<ImmutableList<? extends ConfigurationMetadata>> variantsForGraphTraversal = targetComponent.getVariantsForGraphTraversal();
        ImmutableList<? extends ConfigurationMetadata> consumableConfigurations = variantsForGraphTraversal.or(ImmutableList.<ConfigurationMetadata>of());
        AttributesSchemaInternal producerAttributeSchema = targetComponent.getAttributesSchema();
        AttributeMatcher attributeMatcher = consumerSchema.withProducer(producerAttributeSchema);
        ConfigurationMetadata fallbackConfiguration = targetComponent.getConfiguration(Dependency.DEFAULT_CONFIGURATION);
        if (fallbackConfiguration != null && !fallbackConfiguration.isCanBeConsumed()) {
            fallbackConfiguration = null;
        }
        ModuleVersionIdentifier versionId = targetComponent.getModuleVersionId();
        if (!consumableConfigurations.isEmpty()) {
            ImmutableList<ConfigurationMetadata> variantsProvidingRequestedCapabilities = filterVariantsByRequestedCapabilities(targetComponent, explicitRequestedCapabilities, consumableConfigurations, versionId.getGroup(), versionId.getName(), true);
            if (variantsProvidingRequestedCapabilities.isEmpty()) {
                throw new NoMatchingCapabilitiesException(targetComponent, explicitRequestedCapabilities, consumableConfigurations);
            }
            consumableConfigurations = variantsProvidingRequestedCapabilities;
        }
        List<ConfigurationMetadata> matches = attributeMatcher.matches(consumableConfigurations, consumerAttributes, fallbackConfiguration);
        if (matches.size() > 1) {
            // there's an ambiguity, but we may have several variants matching the requested capabilities.
            // Here we're going to check if in the candidates, there's a single one _strictly_ matching the requested capabilities.
            List<ConfigurationMetadata> strictlyMatchingCapabilities = filterVariantsByRequestedCapabilities(targetComponent, explicitRequestedCapabilities, matches, versionId.getGroup(), versionId.getName(), false);
            if (strictlyMatchingCapabilities.size() == 1) {
                return singleVariant(variantsForGraphTraversal, matches);
            }
        }
        if (matches.size() == 1) {
            return singleVariant(variantsForGraphTraversal, matches);
        } else if (!matches.isEmpty()) {
            throw new AmbiguousConfigurationSelectionException(consumerAttributes, attributeMatcher, matches, targetComponent, variantsForGraphTraversal.isPresent());
        } else {
            throw new NoMatchingConfigurationSelectionException(consumerAttributes, attributeMatcher, targetComponent, variantsForGraphTraversal.isPresent());
        }
    }

    private static ConfigurationMetadata singleVariant(Optional<ImmutableList<? extends ConfigurationMetadata>> variantsForGraphTraversal, List<ConfigurationMetadata> matches) {
        ConfigurationMetadata match = matches.get(0);
        if (variantsForGraphTraversal.isPresent()) {
            return SelectedByVariantMatchingConfigurationMetadata.of(match);
        }
        return match;
    }

    private static ImmutableList<ConfigurationMetadata> filterVariantsByRequestedCapabilities(ComponentResolveMetadata targetComponent, Collection<? extends Capability> explicitRequestedCapabilities, Collection<? extends ConfigurationMetadata> consumableConfigurations, String group, String name, boolean lenient) {
        if (consumableConfigurations.isEmpty()) {
            return ImmutableList.of();
        }
        ImmutableList.Builder<ConfigurationMetadata> builder = ImmutableList.builderWithExpectedSize(consumableConfigurations.size());
        boolean explicitlyRequested = !explicitRequestedCapabilities.isEmpty();
        for (ConfigurationMetadata configuration : consumableConfigurations) {
            List<? extends Capability> capabilities = configuration.getCapabilities().getCapabilities();
            MatchResult result;
            if (explicitlyRequested) {
                // some capabilities are explicitly required (in other words, we're not _necessarily_ looking for the default capability
                // so we need to filter the configurations
                result = providesAllCapabilities(targetComponent, explicitRequestedCapabilities, capabilities);
            } else {
                // we need to make sure the variants we consider provide the implicit capability
                result = containsImplicitCapability(capabilities, group, name);
            }
            if (result.matches) {
                if (lenient || result == MatchResult.EXACT_MATCH) {
                    builder.add(configuration);
                }
            }
        }
        return builder.build();
    }

    /**
     * Determines if a producer variant provides all the requested capabilities. When doing so it does
     * NOT consider capability versions, as they will be used later in the engine during conflict resolution.
     */
    private static MatchResult providesAllCapabilities(ComponentResolveMetadata targetComponent, Collection<? extends Capability> explicitRequestedCapabilities, List<? extends Capability> providerCapabilities) {
        if (providerCapabilities.isEmpty()) {
            // producer doesn't declare anything, so we assume that it only provides the implicit capability
            if (explicitRequestedCapabilities.size() == 1) {
                Capability requested = explicitRequestedCapabilities.iterator().next();
                ModuleVersionIdentifier mvi = targetComponent.getModuleVersionId();
                if (requested.getGroup().equals(mvi.getGroup()) && requested.getName().equals(mvi.getName())) {
                    return MatchResult.EXACT_MATCH;
                }
            }
        }
        for (Capability requested : explicitRequestedCapabilities) {
            String requestedGroup = requested.getGroup();
            String requestedName = requested.getName();
            boolean found = false;
            for (Capability provided : providerCapabilities) {
                if (provided.getGroup().equals(requestedGroup) && provided.getName().equals(requestedName)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return MatchResult.NO_MATCH;
            }
        }
        boolean exactMatch = explicitRequestedCapabilities.size() == providerCapabilities.size();
        return exactMatch ? MatchResult.EXACT_MATCH : MatchResult.MATCHES_ALL;
    }

    private static MatchResult containsImplicitCapability(Collection<? extends Capability> capabilities, String group, String name) {
        if (capabilities.isEmpty()) {
            // An empty capability list means that it's an implicit capability only
            return MatchResult.EXACT_MATCH;
        }
        for (Capability capability : capabilities) {
            if (group.equals(capability.getGroup()) && name.equals(capability.getName())) {
                boolean exactMatch = capabilities.size() == 1;
                return exactMatch ? MatchResult.EXACT_MATCH : MatchResult.MATCHES_ALL;
            }
        }
        return MatchResult.NO_MATCH;
    }

    private enum MatchResult {
        NO_MATCH(false),
        MATCHES_ALL(true),
        EXACT_MATCH(true);

        private final boolean matches;

        MatchResult(boolean match) {
            this.matches = match;
        }
    }
}
