package org.metaborg.spoofax.core.stratego.primitives;

import com.google.common.collect.Lists;
import com.google.inject.Inject;
import java.io.File;
import java.util.List;
import org.apache.commons.vfs2.FileObject;
import org.metaborg.spoofax.core.project.ILanguagePathService;
import org.metaborg.spoofax.core.project.IProject;
import org.metaborg.spoofax.core.project.IProjectService;
import org.metaborg.spoofax.core.resource.IResourceService;
import org.spoofax.interpreter.core.IContext;
import org.spoofax.interpreter.core.InterpreterException;
import org.spoofax.interpreter.core.Tools;
import org.spoofax.interpreter.library.AbstractPrimitive;
import org.spoofax.interpreter.stratego.Strategy;
import org.spoofax.interpreter.terms.IStrategoTerm;
import org.spoofax.interpreter.terms.ITermFactory;

public class LanguageIncludesPrimitive extends AbstractPrimitive {
    private final ILanguagePathService languagePathService;
    private final IResourceService resourceService;
    private final IProjectService projectService;

    @Inject public LanguageIncludesPrimitive(IResourceService resourceService,
            IProjectService projectService,
            ILanguagePathService languagePathService) {
        super("SSL_EXT_language_includes", 0, 1);
        this.projectService = projectService;
        this.languagePathService = languagePathService;
        this.resourceService = resourceService;
    }

    @Override
    public boolean call(IContext env, Strategy[] svars, IStrategoTerm[] tvars)
            throws InterpreterException {
        if ( !Tools.isTermString(tvars[0]) ) {
            return false;
        }
        final ITermFactory factory = env.getFactory();
        final String languageName = Tools.asJavaString(tvars[0]);
        org.metaborg.spoofax.core.context.IContext context =
                (org.metaborg.spoofax.core.context.IContext) env.contextObject();
        IProject project = projectService.get(context.location());
        if ( project == null ) {
            env.setCurrent(factory.makeList());
            return true;
        }
        final Iterable<FileObject> includes =
                languagePathService.includes(project, languageName);
        final List<IStrategoTerm> terms = Lists.newArrayList();
        for ( FileObject include : includes ) {
            File localFile = resourceService.localFile(include);
            terms.add(factory.makeString(localFile.getPath()));
        }
        env.setCurrent(factory.makeList(terms));
        return true;
    }

}
