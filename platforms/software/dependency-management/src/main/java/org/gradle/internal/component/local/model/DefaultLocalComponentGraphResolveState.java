/*
 * Copyright 2022 the original author or authors.
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

package org.gradle.internal.component.local.model;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.gradle.api.Transformer;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.result.ResolvedVariantResult;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvedVariant;
import org.gradle.api.internal.artifacts.result.DefaultResolvedVariantResult;
import org.gradle.api.internal.attributes.AttributeDesugaring;
import org.gradle.api.internal.attributes.AttributesSchemaInternal;
import org.gradle.api.internal.attributes.ImmutableAttributes;
import org.gradle.internal.Describables;
import org.gradle.internal.component.external.model.ImmutableCapabilities;
import org.gradle.internal.component.model.AbstractComponentGraphResolveState;
import org.gradle.internal.component.model.ComponentArtifactMetadata;
import org.gradle.internal.component.model.ComponentArtifactResolveMetadata;
import org.gradle.internal.component.model.ComponentGraphResolveMetadata;
import org.gradle.internal.component.model.ComponentIdGenerator;
import org.gradle.internal.component.model.GraphSelectionCandidates;
import org.gradle.internal.component.model.ImmutableModuleSources;
import org.gradle.internal.component.model.IvyArtifactName;
import org.gradle.internal.component.model.ModuleSources;
import org.gradle.internal.component.model.VariantArtifactGraphResolveMetadata;
import org.gradle.internal.component.model.VariantArtifactResolveState;
import org.gradle.internal.component.model.VariantGraphResolveState;
import org.gradle.internal.component.model.VariantResolveMetadata;
import org.gradle.internal.component.resolution.failure.exception.ConfigurationSelectionException;
import org.gradle.internal.component.resolution.failure.type.ConfigurationNotConsumableFailure;
import org.gradle.internal.lazy.Lazy;
import org.gradle.internal.resolve.resolver.VariantArtifactResolver;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Holds the resolution state for a local component. The state is calculated as required, and an instance can be used for multiple resolutions across a build tree.
 *
 * <p>The aim is to create only a single instance of this type per project and reuse that for all resolution that happens in a build tree. This isn't quite the case yet.
 */
public class DefaultLocalComponentGraphResolveState extends AbstractComponentGraphResolveState<LocalComponentGraphResolveMetadata> implements LocalComponentGraphResolveState {
    private final ComponentIdGenerator idGenerator;
    private final boolean adHoc;
    private final VariantMetadataFactory variantFactory;
    private final Transformer<LocalComponentArtifactMetadata, LocalComponentArtifactMetadata> artifactTransformer;

    // The graph resolve state for variants selected by name
    private final ConcurrentMap<String, LocalVariantGraphResolveState> variants = new ConcurrentHashMap<>();

    // The variants to use for variant selection during graph resolution
    private final Lazy<LocalComponentGraphSelectionCandidates> graphSelectionCandidates;

    // The public view of all selectable variants of this component
    private final Lazy<List<ResolvedVariantResult>> selectableVariantResults;

    public DefaultLocalComponentGraphResolveState(
        long instanceId,
        LocalComponentGraphResolveMetadata metadata,
        AttributeDesugaring attributeDesugaring,
        ComponentIdGenerator idGenerator,
        boolean adHoc,
        VariantMetadataFactory variantFactory,
        @Nullable Transformer<LocalComponentArtifactMetadata, LocalComponentArtifactMetadata> artifactTransformer
    ) {
        super(instanceId, metadata, attributeDesugaring);
        this.idGenerator = idGenerator;
        this.adHoc = adHoc;
        this.variantFactory = variantFactory;
        this.artifactTransformer = artifactTransformer;

        // TODO: We should be using the CalculatedValue infrastructure to lazily compute these values
        //       in order to properly manage project locks.
        this.graphSelectionCandidates = Lazy.locking().of(() ->
            computeGraphSelectionCandidates(this, idGenerator, variantFactory, artifactTransformer)
        );
        this.selectableVariantResults = Lazy.locking().of(() ->
            computeSelectableVariantResults(this)
        );
    }

    @Override
    public void reevaluate() {
        // TODO: This is not thread-safe. We do not atomically clear all the different fields at once.
        variants.clear();
        variantFactory.invalidate();
        // TODO: We are missing logic to invalidate allVariantsForGraphResolution and selectableVariantResults.
    }

    @Override
    public ModuleVersionIdentifier getModuleVersionId() {
        return getMetadata().getModuleVersionId();
    }

    @Override
    public boolean isAdHoc() {
        return adHoc;
    }

    @Override
    public LocalComponentGraphResolveState copy(ComponentIdentifier newComponentId, Transformer<LocalComponentArtifactMetadata, LocalComponentArtifactMetadata> transformer) {
        // Keep track of transformed artifacts as a given artifact may appear in multiple variants
        Map<LocalComponentArtifactMetadata, LocalComponentArtifactMetadata> transformedArtifacts = new HashMap<>();
        Transformer<LocalComponentArtifactMetadata, LocalComponentArtifactMetadata> cachedTransformer = oldArtifact ->
            transformedArtifacts.computeIfAbsent(oldArtifact, transformer::transform);

        LocalComponentGraphResolveMetadata originalMetadata = getMetadata();
        LocalComponentGraphResolveMetadata copiedMetadata = new LocalComponentGraphResolveMetadata(
            originalMetadata.getModuleVersionId(),
            newComponentId,
            originalMetadata.getStatus(),
            originalMetadata.getAttributesSchema()
        );

        return new DefaultLocalComponentGraphResolveState(
            idGenerator.nextComponentId(),
            copiedMetadata,
            getAttributeDesugaring(),
            idGenerator,
            adHoc,
            variantFactory,
            cachedTransformer
        );
    }

    @Override
    public ComponentArtifactResolveMetadata getArtifactMetadata() {
        return new LocalComponentArtifactResolveMetadata(getMetadata());
    }

    @Override
    public LocalComponentGraphSelectionCandidates getCandidatesForGraphVariantSelection() {
        return graphSelectionCandidates.get();
    }

    private static LocalComponentGraphSelectionCandidates computeGraphSelectionCandidates(
        DefaultLocalComponentGraphResolveState component,
        ComponentIdGenerator idGenerator,
        VariantMetadataFactory variantFactory,
        @Nullable Transformer<LocalComponentArtifactMetadata, LocalComponentArtifactMetadata> artifactTransformer
    ) {
        ImmutableList.Builder<LocalVariantGraphResolveState> variantsWithAttributes = new ImmutableList.Builder<>();
        ImmutableMap.Builder<String, LocalVariantGraphResolveState> variantsByName = ImmutableMap.builder();

        variantFactory.visitConsumableVariants(variant -> {
            if (artifactTransformer != null) {
                variant = variant.copyWithTransformedArtifacts(artifactTransformer);
            }

            LocalVariantGraphResolveState variantState = new DefaultLocalVariantGraphResolveState(
                idGenerator.nextVariantId(), component, component.getMetadata(), variant
            );

            if (!variant.getAttributes().isEmpty()) {
                variantsWithAttributes.add(variantState);
            }

            variantsByName.put(variant.getName(), variantState);
        });

        return new DefaultLocalComponentGraphSelectionCandidates(
            variantsWithAttributes.build(),
            variantsByName.build(),
            component
        );
    }

    @Override
    public List<ResolvedVariantResult> getAllSelectableVariantResults() {
        return selectableVariantResults.get();
    }
    private static List<ResolvedVariantResult> computeSelectableVariantResults(DefaultLocalComponentGraphResolveState component) {
        GraphSelectionCandidates candidates = component.getCandidatesForGraphVariantSelection();
        if (!candidates.supportsAttributeMatching()) {
            return Collections.emptyList();
        }

        return candidates
            .getVariantsForAttributeMatching()
            .stream()
            .flatMap(variant -> variant.prepareForArtifactResolution().getArtifactVariants().stream())
            .map(variant -> new DefaultResolvedVariantResult(
                component.getId(),
                Describables.of(variant.getName()),
                component.getAttributeDesugaring().desugar(variant.getAttributes().asImmutable()),
                component.capabilitiesFor(variant.getCapabilities()),
                null
            ))
            .collect(Collectors.toList());
    }


    @Nullable
    @Override
    public LocalVariantGraphResolveState getVariantByConfigurationName(String configurationName) {
        return variants.computeIfAbsent(configurationName, n -> {
            LocalVariantGraphResolveMetadata variant = variantFactory.getVariantByConfigurationName(configurationName);
            if (variant == null) {
                return null;
            }
            if (artifactTransformer != null) {
                variant = variant.copyWithTransformedArtifacts(artifactTransformer);
            }
            return new DefaultLocalVariantGraphResolveState(idGenerator.nextVariantId(), this, getMetadata(), variant);
        });
    }

    private static class DefaultLocalVariantGraphResolveState extends AbstractVariantGraphResolveState implements LocalVariantGraphResolveState {
        private final long instanceId;
        private final LocalVariantGraphResolveMetadata variant;
        private final Lazy<DefaultLocalVariantArtifactResolveState> artifactResolveState;

        public DefaultLocalVariantGraphResolveState(long instanceId, AbstractComponentGraphResolveState<?> componentState, ComponentGraphResolveMetadata component, LocalVariantGraphResolveMetadata variant) {
            super(componentState);
            this.instanceId = instanceId;
            this.variant = variant;
            // We deliberately avoid locking the initialization of `artifactResolveState`.
            // This object may be shared across multiple worker threads, and the computation of
            // `legacyVariants` below is likely to require acquiring the state lock for the
            // project that owns this `Configuration` leading to a potential deadlock situation.
            // For instance, a thread could acquire the `artifactResolveState` lock while another thread,
            // which already owns the project lock, attempts to acquire the `artifactResolveState` lock.
            // See https://github.com/gradle/gradle/issues/25416
            this.artifactResolveState = Lazy.atomic().of(() -> {
                Set<? extends VariantResolveMetadata> legacyVariants = variant.prepareToResolveArtifacts().getVariants();
                return new DefaultLocalVariantArtifactResolveState(component, variant, legacyVariants);
            });
        }

        @Override
        public long getInstanceId() {
            return instanceId;
        }

        @Override
        public String getName() {
            return variant.getName();
        }

        @Override
        public String toString() {
            return variant.toString();
        }

        @Override
        public LocalVariantGraphResolveMetadata getMetadata() {
            return variant;
        }

        @Override
        public ImmutableAttributes getAttributes() {
            return variant.getAttributes();
        }

        @Override
        public ImmutableCapabilities getCapabilities() {
            return variant.getCapabilities();
        }

        @Override
        public VariantArtifactGraphResolveMetadata resolveArtifacts() {
            return artifactResolveState.get();
        }

        @Override
        public VariantArtifactResolveState prepareForArtifactResolution() {
            return artifactResolveState.get();
        }
    }

    private static class DefaultLocalVariantArtifactResolveState implements VariantArtifactResolveState, VariantArtifactGraphResolveMetadata {
        private final ComponentGraphResolveMetadata component;
        private final LocalVariantGraphResolveMetadata graphVariant;
        private final Set<? extends VariantResolveMetadata> artifactVariants;

        public DefaultLocalVariantArtifactResolveState(ComponentGraphResolveMetadata component, LocalVariantGraphResolveMetadata graphVariant, Set<? extends VariantResolveMetadata> artifactVariants) {
            this.component = component;
            this.graphVariant = graphVariant;
            this.artifactVariants = artifactVariants;
        }

        @Override
        public List<? extends ComponentArtifactMetadata> getArtifacts() {
            return graphVariant.prepareToResolveArtifacts().getArtifacts();
        }

        @Override
        public ResolvedVariant resolveAdhocVariant(VariantArtifactResolver variantResolver, List<IvyArtifactName> dependencyArtifacts) {
            ImmutableList.Builder<ComponentArtifactMetadata> artifacts = ImmutableList.builderWithExpectedSize(dependencyArtifacts.size());
            for (IvyArtifactName dependencyArtifact : dependencyArtifacts) {
                artifacts.add(getArtifactWithName(dependencyArtifact));
            }
            return variantResolver.resolveAdhocVariant(new LocalComponentArtifactResolveMetadata(component), artifacts.build());
        }

        private ComponentArtifactMetadata getArtifactWithName(IvyArtifactName ivyArtifactName) {
            for (ComponentArtifactMetadata candidate : getArtifacts()) {
                if (candidate.getName().equals(ivyArtifactName)) {
                    return candidate;
                }
            }

            return new MissingLocalArtifactMetadata(component.getId(), ivyArtifactName);
        }

        @Override
        public Set<? extends VariantResolveMetadata> getArtifactVariants() {
            return artifactVariants;
        }
    }

    private static class LocalComponentArtifactResolveMetadata implements ComponentArtifactResolveMetadata {
        private final ComponentGraphResolveMetadata metadata;

        public LocalComponentArtifactResolveMetadata(ComponentGraphResolveMetadata metadata) {
            this.metadata = metadata;
        }

        @Override
        public ComponentIdentifier getId() {
            return metadata.getId();
        }

        @Override
        public ModuleVersionIdentifier getModuleVersionId() {
            return metadata.getModuleVersionId();
        }

        @Override
        public ModuleSources getSources() {
            return ImmutableModuleSources.of();
        }

        @Override
        public ImmutableAttributes getAttributes() {
            return ImmutableAttributes.EMPTY;
        }

        @Override
        public AttributesSchemaInternal getAttributesSchema() {
            return metadata.getAttributesSchema();
        }
    }

    private static class DefaultLocalComponentGraphSelectionCandidates implements LocalComponentGraphSelectionCandidates {
        private final List<? extends LocalVariantGraphResolveState> variantsWithAttributes;
        private final Map<String, LocalVariantGraphResolveState> variantsByConfigurationName;
        private final LocalComponentGraphResolveState component;

        public DefaultLocalComponentGraphSelectionCandidates(
            List<? extends LocalVariantGraphResolveState> variantsWithAttributes,
            Map<String, LocalVariantGraphResolveState> variantsByConfigurationName,
            LocalComponentGraphResolveState component
        ) {
            this.variantsWithAttributes = variantsWithAttributes;
            this.variantsByConfigurationName = variantsByConfigurationName;
            this.component = component;
        }

        @Override
        public boolean supportsAttributeMatching() {
            return !variantsWithAttributes.isEmpty();
        }

        @Override
        public List<? extends VariantGraphResolveState> getVariantsForAttributeMatching() {
            if (variantsWithAttributes.isEmpty()) {
                throw new IllegalStateException("No variants available for attribute matching.");
            }
            return variantsWithAttributes;
        }

        @Nullable
        @Override
        public VariantGraphResolveState getVariantByConfigurationName(String name) {
            LocalVariantGraphResolveState variant = variantsByConfigurationName.get(name);
            if (variant != null) {
                return variant;
            }

            // `variantsByConfigurationName` contains only consumable variants.
            // We can ask the component directly via getVariantByConfigurationName, which
            // will return variants based off of non-consumable configurations.
            variant = component.getVariantByConfigurationName(name);
            if (variant == null) {
                return null;
            }

            // If the configuration exists, it must not be consumable, since variantsByConfigurationName contains
            // all consumable configurations.
            assert !variant.getMetadata().isCanBeConsumed() : "Expected configuration to be non-consumable";

            ConfigurationNotConsumableFailure failure = new ConfigurationNotConsumableFailure(name, component.getId().getDisplayName());
            String message = String.format("Selected configuration '" + failure.getRequestedName() + "' on '" + failure.getRequestedComponentDisplayName() + "' but it can't be used as a project dependency because it isn't intended for consumption by other components.");
            throw new ConfigurationSelectionException(message, failure, Collections.emptyList());
        }

        @Override
        public List<LocalVariantGraphResolveState> getAllSelectableVariants() {
            // Find the names of all selectable variants that are not in the variantsWithAttributes
            Set<String> configurationNames = new HashSet<>(variantsByConfigurationName.keySet());
            for (VariantGraphResolveState variant : variantsWithAttributes) {
                configurationNames.remove(variant.getName());
            }

            // Join the list of variants with attributes with the list of variants by configuration name
            List<LocalVariantGraphResolveState> result = new ArrayList<>(variantsWithAttributes);
            for (String configurationName : configurationNames) {
                result.add(variantsByConfigurationName.get(configurationName));
            }

            return result;
        }
    }

    /**
     * Constructs {@link LocalVariantGraphResolveMetadata} instances advertised by a given component.
     * This allows the component metadata to source variant data from multiple sources, both lazy and eager.
     */
    public interface VariantMetadataFactory {

        /**
         * Visit all variants in this component that can be selected in a dependency graph.
         *
         * <p>This includes all variants with and without attributes. Variants visited
         * by this method may not be suitable for selection via attribute matching.</p>
         */
        void visitConsumableVariants(Consumer<LocalVariantGraphResolveMetadata> visitor);

        /**
         * Invalidates any caching used for producing variant metadata.
         */
        void invalidate();

        /**
         * Produces a variant metadata instance from the configuration with the given {@code name}.
         *
         * @return Null if the variant with the given configuration name does not exist.
         */
        @Nullable
        LocalVariantGraphResolveMetadata getVariantByConfigurationName(String name);

        /**
         * Get all names such that {@link #getVariantByConfigurationName(String)} return a non-null value.
         */
        Set<String> getConfigurationNames();
    }
}
