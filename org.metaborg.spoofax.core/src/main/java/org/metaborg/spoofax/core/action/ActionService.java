package org.metaborg.spoofax.core.action;

import java.util.Collection;

import org.metaborg.core.action.IActionService;
import org.metaborg.core.action.ITransformAction;
import org.metaborg.core.action.ITransformGoal;
import org.metaborg.core.action.TransformActionContribution;
import org.metaborg.core.language.FacetContribution;
import org.metaborg.core.language.ILanguageComponent;
import org.metaborg.core.language.ILanguageImpl;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

public class ActionService implements IActionService {
    @Override public Iterable<ITransformAction> action(ILanguageImpl language, ITransformGoal goal) {
        final Iterable<ActionFacet> facets = language.facets(ActionFacet.class);
        final Collection<ITransformAction> actions = Lists.newLinkedList();
        for(ActionFacet facet : facets) {
            Iterables.addAll(actions, facet.actions(goal));
        }
        return actions;
    }

    @Override public Iterable<TransformActionContribution> actionContribution(ILanguageImpl language,
        ITransformGoal goal) {
        final Iterable<FacetContribution<ActionFacet>> facetsContributions =
            language.facetContributions(ActionFacet.class);
        final Collection<TransformActionContribution> actionContributions = Lists.newLinkedList();
        for(FacetContribution<ActionFacet> facetContribution : facetsContributions) {
            final ActionFacet facet = facetContribution.facet;
            final ILanguageComponent component = facetContribution.contributor;
            for(ITransformAction action : facet.actions(goal)) {
                actionContributions.add(new TransformActionContribution(action, component));
            }
        }
        return actionContributions;
    }

    @Override public boolean available(ILanguageImpl language, ITransformGoal goal) {
        final Iterable<ActionFacet> facets = language.facets(ActionFacet.class);
        for(ActionFacet facet : facets) {
            if(!Iterables.isEmpty(facet.actions(goal))) {
                return true;
            }
        }
        return false;
    }
}
