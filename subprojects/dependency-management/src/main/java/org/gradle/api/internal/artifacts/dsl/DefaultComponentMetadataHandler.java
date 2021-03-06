/*
 * Copyright 2013 the original author or authors.
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
package org.gradle.api.internal.artifacts.dsl;

import com.google.common.collect.Interner;
import com.google.common.collect.Sets;
import groovy.lang.Closure;
import org.gradle.api.Action;
import org.gradle.api.ActionConfiguration;
import org.gradle.api.InvalidUserCodeException;
import org.gradle.api.artifacts.ComponentMetadataContext;
import org.gradle.api.artifacts.ComponentMetadataDetails;
import org.gradle.api.artifacts.ComponentMetadataRule;
import org.gradle.api.artifacts.ModuleIdentifier;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.dsl.ComponentMetadataHandler;
import org.gradle.api.artifacts.ivy.IvyModuleDescriptor;
import org.gradle.api.internal.artifacts.ComponentMetadataProcessor;
import org.gradle.api.internal.artifacts.ComponentMetadataProcessorFactory;
import org.gradle.api.internal.artifacts.ImmutableModuleIdentifierFactory;
import org.gradle.api.internal.artifacts.MetadataResolutionContext;
import org.gradle.api.internal.artifacts.repositories.resolver.DependencyConstraintMetadataImpl;
import org.gradle.api.internal.artifacts.repositories.resolver.DirectDependencyMetadataImpl;
import org.gradle.api.internal.attributes.ImmutableAttributesFactory;
import org.gradle.api.internal.changedetection.state.isolation.IsolatableFactory;
import org.gradle.api.internal.notations.ComponentIdentifierParserFactory;
import org.gradle.api.internal.notations.DependencyMetadataNotationParser;
import org.gradle.api.internal.notations.ModuleIdentifierNotationConverter;
import org.gradle.api.specs.Spec;
import org.gradle.api.specs.Specs;
import org.gradle.internal.action.ConfigurableRule;
import org.gradle.internal.action.DefaultConfigurableRule;
import org.gradle.internal.reflect.Instantiator;
import org.gradle.internal.resolve.caching.ComponentMetadataRuleExecutor;
import org.gradle.internal.rules.DefaultRuleActionAdapter;
import org.gradle.internal.rules.DefaultRuleActionValidator;
import org.gradle.internal.rules.RuleAction;
import org.gradle.internal.rules.RuleActionAdapter;
import org.gradle.internal.rules.RuleActionValidator;
import org.gradle.internal.rules.SpecRuleAction;
import org.gradle.internal.typeconversion.NotationParser;
import org.gradle.internal.typeconversion.NotationParserBuilder;
import org.gradle.internal.typeconversion.UnsupportedNotationException;

import java.util.Collections;
import java.util.List;
import java.util.Set;

public class DefaultComponentMetadataHandler implements ComponentMetadataHandler, ComponentMetadataProcessorFactory {
    private static final String ADAPTER_NAME = ComponentMetadataHandler.class.getSimpleName();
    private static final List<Class<?>> VALIDATOR_PARAM_LIST = Collections.<Class<?>>singletonList(IvyModuleDescriptor.class);
    private static final String INVALID_SPEC_ERROR = "Could not add a component metadata rule for module '%s'.";

    private final Instantiator instantiator;
    private final Set<SpecRuleAction<? super ComponentMetadataDetails>> rules = Sets.newLinkedHashSet();
    private final Set<SpecConfigurableRule> classBasedRules = Sets.newLinkedHashSet();
    private final RuleActionAdapter ruleActionAdapter;
    private final NotationParser<Object, ModuleIdentifier> moduleIdentifierNotationParser;
    private final NotationParser<Object, DirectDependencyMetadataImpl> dependencyMetadataNotationParser;
    private final NotationParser<Object, DependencyConstraintMetadataImpl> dependencyConstraintMetadataNotationParser;
    private final NotationParser<Object, ComponentIdentifier> componentIdentifierNotationParser;
    private final ImmutableAttributesFactory attributesFactory;
    private final IsolatableFactory isolatableFactory;
    private final ComponentMetadataRuleExecutor ruleExecutor;

    DefaultComponentMetadataHandler(Instantiator instantiator,
                                    RuleActionAdapter ruleActionAdapter,
                                    ImmutableModuleIdentifierFactory moduleIdentifierFactory,
                                    Interner<String> stringInterner,
                                    ImmutableAttributesFactory attributesFactory,
                                    IsolatableFactory isolatableFactory,
                                    ComponentMetadataRuleExecutor ruleExecutor) {
        this.instantiator = instantiator;
        this.ruleActionAdapter = ruleActionAdapter;
        this.moduleIdentifierNotationParser = NotationParserBuilder
            .toType(ModuleIdentifier.class)
            .converter(new ModuleIdentifierNotationConverter(moduleIdentifierFactory))
            .toComposite();
        this.ruleExecutor = ruleExecutor;
        this.dependencyMetadataNotationParser = DependencyMetadataNotationParser.parser(instantiator, DirectDependencyMetadataImpl.class, stringInterner);
        this.dependencyConstraintMetadataNotationParser = DependencyMetadataNotationParser.parser(instantiator, DependencyConstraintMetadataImpl.class, stringInterner);
        this.componentIdentifierNotationParser = new ComponentIdentifierParserFactory().create();
        this.attributesFactory = attributesFactory;
        this.isolatableFactory = isolatableFactory;
    }

    public DefaultComponentMetadataHandler(Instantiator instantiator, ImmutableModuleIdentifierFactory moduleIdentifierFactory, Interner<String> stringInterner, ImmutableAttributesFactory attributesFactory, IsolatableFactory isolatableFactory, ComponentMetadataRuleExecutor ruleExecutor) {
        this(instantiator, createAdapter(), moduleIdentifierFactory, stringInterner, attributesFactory, isolatableFactory, ruleExecutor);
    }

    private static RuleActionAdapter createAdapter() {
        RuleActionValidator ruleActionValidator = new DefaultRuleActionValidator(VALIDATOR_PARAM_LIST);
        return new DefaultRuleActionAdapter(ruleActionValidator, ADAPTER_NAME);
    }

    private ComponentMetadataHandler addRule(SpecRuleAction<? super ComponentMetadataDetails> ruleAction) {
        if (!classBasedRules.isEmpty()) {
            throw new IllegalArgumentException("Non class based component metadata rules must all be added before class based ones.");
        }
        rules.add(ruleAction);
        return this;
    }

    private ComponentMetadataHandler addClassBasedRule(SpecConfigurableRule ruleAction) {
        classBasedRules.add(ruleAction);
        return this;
    }

    private <U> SpecRuleAction<? super U> createAllSpecRuleAction(RuleAction<? super U> ruleAction) {
        return new SpecRuleAction<U>(ruleAction, Specs.<U>satisfyAll());
    }

    private SpecRuleAction<? super ComponentMetadataDetails> createSpecRuleActionForModule(Object id, RuleAction<? super ComponentMetadataDetails> ruleAction) {
        ModuleIdentifier moduleIdentifier;

        try {
            moduleIdentifier = moduleIdentifierNotationParser.parseNotation(id);
        } catch (UnsupportedNotationException e) {
            throw new InvalidUserCodeException(String.format(INVALID_SPEC_ERROR, id == null ? "null" : id.toString()), e);
        }

        Spec<ComponentMetadataDetails> spec = new ComponentMetadataDetailsMatchingSpec(moduleIdentifier);
        return new SpecRuleAction<ComponentMetadataDetails>(ruleAction, spec);
    }

    public ComponentMetadataHandler all(Action<? super ComponentMetadataDetails> rule) {
        return addRule(createAllSpecRuleAction(ruleActionAdapter.createFromAction(rule)));
    }

    public ComponentMetadataHandler all(Closure<?> rule) {
        return addRule(createAllSpecRuleAction(ruleActionAdapter.createFromClosure(ComponentMetadataDetails.class, rule)));
    }

    public ComponentMetadataHandler all(Object ruleSource) {
        return addRule(createAllSpecRuleAction(ruleActionAdapter.createFromRuleSource(ComponentMetadataDetails.class, ruleSource)));
    }

    public ComponentMetadataHandler withModule(Object id, Action<? super ComponentMetadataDetails> rule) {
        return addRule(createSpecRuleActionForModule(id, ruleActionAdapter.createFromAction(rule)));
    }

    public ComponentMetadataHandler withModule(Object id, Closure<?> rule) {
        return addRule(createSpecRuleActionForModule(id, ruleActionAdapter.createFromClosure(ComponentMetadataDetails.class, rule)));
    }

    public ComponentMetadataHandler withModule(Object id, Object ruleSource) {
        return addRule(createSpecRuleActionForModule(id, ruleActionAdapter.createFromRuleSource(ComponentMetadataDetails.class, ruleSource)));
    }

    @Override
    public ComponentMetadataHandler all(Class<? extends ComponentMetadataRule> rule) {
        return addClassBasedRule(createAllSpecConfigurableRule(DefaultConfigurableRule.<ComponentMetadataContext>of(rule)));
    }

    @Override
    public ComponentMetadataHandler all(Class<? extends ComponentMetadataRule> rule, Action<? super ActionConfiguration> configureAction) {
        return addClassBasedRule(createAllSpecConfigurableRule(DefaultConfigurableRule.<ComponentMetadataContext>of(rule, configureAction, isolatableFactory)));
    }

    @Override
    public ComponentMetadataHandler withModule(Object id, Class<? extends ComponentMetadataRule> rule) {
        return addClassBasedRule(createModuleSpecConfigurableRule(id, DefaultConfigurableRule.<ComponentMetadataContext>of(rule)));
    }

    @Override
    public ComponentMetadataHandler withModule(Object id, Class<? extends ComponentMetadataRule> rule, Action<? super ActionConfiguration> configureAction) {
        return addClassBasedRule(createModuleSpecConfigurableRule(id, DefaultConfigurableRule.<ComponentMetadataContext>of(rule, configureAction, isolatableFactory)));
    }

    private SpecConfigurableRule createModuleSpecConfigurableRule(Object id, ConfigurableRule<ComponentMetadataContext> instantiatingAction) {
        ModuleIdentifier moduleIdentifier;

        try {
            moduleIdentifier = moduleIdentifierNotationParser.parseNotation(id);
        } catch (UnsupportedNotationException e) {
            throw new InvalidUserCodeException(String.format(INVALID_SPEC_ERROR, id == null ? "null" : id.toString()), e);
        }

        Spec<ModuleVersionIdentifier> spec = new ModuleVersionIdentifierSpec(moduleIdentifier);
        return new SpecConfigurableRule(instantiatingAction, spec);
    }

    private SpecConfigurableRule createAllSpecConfigurableRule(ConfigurableRule<ComponentMetadataContext> instantiatingAction) {
        return new SpecConfigurableRule(instantiatingAction, Specs.<ModuleVersionIdentifier>satisfyAll());
    }

    @Override
    public ComponentMetadataProcessor createComponentMetadataProcessor(MetadataResolutionContext resolutionContext) {
        return new DefaultComponentMetadataProcessor(rules, classBasedRules, instantiator, dependencyMetadataNotationParser, dependencyConstraintMetadataNotationParser, componentIdentifierNotationParser, attributesFactory, ruleExecutor, resolutionContext);
    }

    static class ComponentMetadataDetailsMatchingSpec implements Spec<ComponentMetadataDetails> {
        private ModuleIdentifier target;

        ComponentMetadataDetailsMatchingSpec(ModuleIdentifier target) {
            this.target = target;
        }

        public boolean isSatisfiedBy(ComponentMetadataDetails componentMetadataDetails) {
            ModuleVersionIdentifier identifier = componentMetadataDetails.getId();
            return identifier.getGroup().equals(target.getGroup()) && identifier.getName().equals(target.getName());
        }
    }

    static class ModuleVersionIdentifierSpec implements Spec<ModuleVersionIdentifier> {
        private ModuleIdentifier target;

        ModuleVersionIdentifierSpec(ModuleIdentifier target) {
            this.target = target;
        }

        public boolean isSatisfiedBy(ModuleVersionIdentifier identifier) {
            return identifier.getGroup().equals(target.getGroup()) && identifier.getName().equals(target.getName());
        }
    }

}
