package org.metaborg.spoofax.meta.core;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Set;

import javax.annotation.Nullable;

import org.apache.commons.vfs2.AllFileSelector;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.metaborg.core.MetaborgException;
import org.metaborg.core.action.CompileGoal;
import org.metaborg.core.action.EndNamedGoal;
import org.metaborg.core.build.BuildInput;
import org.metaborg.core.build.BuildInputBuilder;
import org.metaborg.core.build.ConsoleBuildMessagePrinter;
import org.metaborg.core.build.dependency.IDependencyService;
import org.metaborg.core.build.paths.ILanguagePathService;
import org.metaborg.core.config.ILanguageComponentConfig;
import org.metaborg.core.config.ILanguageComponentConfigBuilder;
import org.metaborg.core.config.ILanguageComponentConfigWriter;
import org.metaborg.core.source.ISourceTextService;
import org.metaborg.spoofax.core.processing.ISpoofaxProcessorRunner;
import org.metaborg.spoofax.core.project.settings.StrategoFormat;
import org.metaborg.spoofax.generator.language.LanguageSpecGenerator;
import org.metaborg.spoofax.meta.core.config.ISpoofaxLanguageSpecConfig;
import org.metaborg.spoofax.meta.core.config.ISpoofaxLanguageSpecConfigWriter;
import org.metaborg.spoofax.meta.core.pluto.SpoofaxContext;
import org.metaborg.spoofax.meta.core.pluto.SpoofaxReporting;
import org.metaborg.spoofax.meta.core.pluto.build.main.GenerateSourcesBuilder;
import org.metaborg.spoofax.meta.core.pluto.build.main.PackageBuilder;
import org.metaborg.spoofax.meta.core.project.ISpoofaxLanguageSpecPaths;
import org.metaborg.spoofax.meta.core.project.GeneratorSettings;
import org.metaborg.util.file.FileAccess;
import org.metaborg.util.log.ILogger;
import org.metaborg.util.log.LoggerUtils;
import org.metaborg.util.resource.FileSelectorUtils;

import build.pluto.builder.BuildManagers;
import build.pluto.builder.BuildRequest;
import build.pluto.builder.RequiredBuilderFailed;
import build.pluto.output.Output;
import build.pluto.xattr.Xattr;

import com.google.common.collect.Lists;
import com.google.inject.Inject;
import com.google.inject.Injector;

// TODO Rename to: SpoofaxLanguageSpecBuilder
public class SpoofaxMetaBuilder {
    private static final ILogger logger = LoggerUtils.logger(SpoofaxMetaBuilder.class);
    private static final String failingRebuildMessage =
        "Previous build failed and no change in the build input has been observed, not rebuilding. Fix the problem, or clean and rebuild the project to force a rebuild";

    private final Injector injector;
    private final ISourceTextService sourceTextService;
    private final IDependencyService dependencyService;
    private final ILanguagePathService languagePathService;
    private final ISpoofaxProcessorRunner runner;
    private final Set<IBuildStep> buildSteps;
    private final ILanguageComponentConfigBuilder componentConfigBuilder;
    private final ILanguageComponentConfigWriter componentConfigWriter;
    private final ISpoofaxLanguageSpecConfigWriter languageSpecConfigWriter;


    @Inject public SpoofaxMetaBuilder(Injector injector, ISourceTextService sourceTextService,
        IDependencyService dependencyService, ILanguagePathService languagePathService, ISpoofaxProcessorRunner runner,
        Set<IBuildStep> buildSteps, ILanguageComponentConfigBuilder componentConfigBuilder,
        ILanguageComponentConfigWriter componentConfigWriter,
        ISpoofaxLanguageSpecConfigWriter languageSpecConfigWriter) {
        this.injector = injector;
        this.sourceTextService = sourceTextService;
        this.dependencyService = dependencyService;
        this.languagePathService = languagePathService;
        this.runner = runner;
        this.componentConfigBuilder = componentConfigBuilder;
        this.componentConfigWriter = componentConfigWriter;
        this.languageSpecConfigWriter = languageSpecConfigWriter;
        this.buildSteps = buildSteps;
    }


    public void initialize(SpoofaxLanguageSpecBuildInput input) throws FileSystemException {
        input.languageSpec.paths().includeFolder().createFolder();
        input.languageSpec.paths().libFolder().createFolder();
        input.languageSpec.paths().generatedSourceFolder().createFolder();
        input.languageSpec.paths().generatedSyntaxFolder().createFolder();
    }

    public void generateSources(SpoofaxLanguageSpecBuildInput input, FileAccess access) throws Exception {
        final FileObject location = input.languageSpec.location();
        logger.debug("Generating sources for {}", input.languageSpec.location());

        final LanguageSpecGenerator generator = new LanguageSpecGenerator(
            new GeneratorSettings(input.languageSpec.config(), input.languageSpec.paths()), access);
        generator.generateAll();

        componentConfigBuilder.reset();
        componentConfigBuilder.copyFrom(input.languageSpec.config());
        final ILanguageComponentConfig config = componentConfigBuilder.build(location);
        componentConfigWriter.write(location, config, access);

        // FIXME: This is temporary, until we've moved to the new config system completely.
        // As there's then no need to write the config file.
        if(!this.languageSpecConfigWriter.getConfigFile(input.languageSpec).exists()) {
            this.languageSpecConfigWriter.write(input.languageSpec, input.languageSpec.config(), access);
        }
    }

    public void compilePreJava(SpoofaxLanguageSpecBuildInput input) throws MetaborgException {
        logger.debug("Running pre-Java build for {}", input.languageSpec.location());

        for(IBuildStep buildStep : buildSteps) {
            buildStep.compilePreJava(input);
        }

        initPluto();
        try {
            plutoBuild(GenerateSourcesBuilder.request(generateSourcesBuilderInput(input)));
        } catch(RequiredBuilderFailed e) {
            if(e.getMessage().contains("no rebuild of failing builder")) {
                throw new MetaborgException(failingRebuildMessage);
            }
            throw new MetaborgException();
        } catch(RuntimeException e) {
            throw e;
        } catch(Throwable e) {
            throw new MetaborgException(e);
        }


        // HACK: compile the main ESV file to make sure that packed.esv file is always available.
        final FileObject mainEsvFile = input.languageSpec.paths().mainEsvFile();
        try {
            if(mainEsvFile.exists()) {
                logger.info("Compiling ESV file {}", mainEsvFile);
                // @formatter:off
                final BuildInput buildInput =
                        new BuildInputBuilder(input.languageSpec)
                                .addSource(mainEsvFile)
                                .addTransformGoal(new CompileGoal())
                                .withMessagePrinter(new ConsoleBuildMessagePrinter(sourceTextService, false, true, logger))
                                .build(dependencyService, languagePathService);
                // @formatter:on
                runner.build(buildInput, null, null).schedule().block();
            }
        } catch(FileSystemException e) {
            final String message = logger.format("Could not compile ESV file {}", mainEsvFile);
            throw new MetaborgException(message, e);
        } catch(InterruptedException e) {
            // Ignore
        }

        // HACK: compile the main DS file if available, after generating sources (because ds can depend on Stratego
        // strategies), to generate an interpreter.
        final FileObject mainDsFile = input.languageSpec.paths().dsMainFile();
        try {
            if(mainDsFile.exists()) {
                logger.info("Compiling DynSem file {}", mainDsFile);
                // @formatter:off
                final BuildInput buildInput =
                    new BuildInputBuilder(input.languageSpec)
                    .addSource(mainDsFile)
                    .addTransformGoal(new EndNamedGoal("All to Java"))
                    .withMessagePrinter(new ConsoleBuildMessagePrinter(sourceTextService, false, true, logger))
                    .build(dependencyService, languagePathService);
                // @formatter:on
                runner.build(buildInput, null, null).schedule().block();
            }
        } catch(FileSystemException e) {
            final String message = logger.format("Could not compile DynSem file {}", mainDsFile);
            throw new MetaborgException(message, e);
        } catch(InterruptedException e) {
            // Ignore
        }
    }

    public void compilePostJava(SpoofaxLanguageSpecBuildInput input) throws MetaborgException {
        logger.debug("Running post-Java build for {}", input.languageSpec.location());

        for(IBuildStep buildStep : buildSteps) {
            buildStep.compilePostJava(input);
        }

        initPluto();
        try {
            plutoBuild(PackageBuilder.request(packageBuilderInput(input)));
        } catch(RequiredBuilderFailed e) {
            if(e.getMessage().contains("no rebuild of failing builder")) {
                throw new MetaborgException(failingRebuildMessage);
            }
            throw new MetaborgException();
        } catch(RuntimeException e) {
            throw e;
        } catch(Throwable e) {
            throw new MetaborgException(e);
        }
    }

    public void clean(SpoofaxLanguageSpecBuildInput input) throws IOException {
        logger.debug("Cleaning {}", input.languageSpec.location());
        final AllFileSelector selector = new AllFileSelector();
        input.languageSpec.paths().strJavaTransFolder().delete(selector);
        input.languageSpec.paths().includeFolder().delete(selector);
        input.languageSpec.paths().generatedSourceFolder().delete(selector);
        input.languageSpec.paths().cacheFolder().delete(selector);
        input.languageSpec.paths().dsGeneratedInterpreterJava().delete(FileSelectorUtils.extension("java"));
        Xattr.getDefault().clear();
    }


    private void initPluto() {
        SpoofaxContext.init(injector);
    }

    private <Out extends Output> Out plutoBuild(BuildRequest<?, Out, ?, ?> buildRequest) throws Throwable {
        return BuildManagers.build(buildRequest, new SpoofaxReporting());
    }

    private GenerateSourcesBuilder.Input generateSourcesBuilderInput(SpoofaxLanguageSpecBuildInput input) {
        final ISpoofaxLanguageSpecConfig config = input.languageSpec.config();
        final ISpoofaxLanguageSpecPaths paths = input.languageSpec.paths();
        final SpoofaxContext context = new SpoofaxContext(input.languageSpec.location(), paths.buildFolder());

        final String module = config.sdfName();
        final String metaModule = config.metaSdfName();
        final File strategoMainFile = context.toFile(paths.strMainFile());
        final File strategoJavaStrategiesMainFile = context.toFile(paths.strJavaStrategiesMainFile());

        @Nullable final File externalDef;
        final String externalDefStr = config.externalDef();
        if(externalDefStr != null) {
            externalDef = context.toFile(context.resourceService().resolve(externalDefStr));
        } else {
            externalDef = null;
        }
        final File packSdfInputPath = context.toFile(paths.getSdfMainFile(module));
        final File packSdfOutputPath = context.toFile(paths.getSdfCompiledDefFile(module));
        final File packMetaSdfInputPath = context.toFile(paths.getSdfMainFile(metaModule));
        final File packMetaSdfOutputPath = context.toFile(paths.getSdfCompiledDefFile(metaModule));
        final File syntaxFolder = context.toFile(paths.syntaxFolder());
        final File genSyntaxFolder = context.toFile(paths.generatedSyntaxFolder());

        final File makePermissiveOutputPath = context.toFile(paths.getSdfCompiledPermissiveDefFile(module));
        final File sdf2tableOutputPath = context.toFile(paths.getSdfCompiledTableFile(module));

        final File ppGenInputPath = context.toFile(paths.getSdfCompiledDefFile(module));
        final File ppGenOutputPath = context.toFile(paths.getGeneratedPpCompiledFile(module));
        final File afGenOutputPath = context.toFile(paths.getGeneratedPpAfCompiledFile(module));

        final File ppPackInputPath = context.toFile(paths.getPpFile(module));
        final File ppPackOutputPath = context.toFile(paths.getPpAfCompiledFile(module));

        final File sdf2ParenthesizeInputFile = context.toFile(paths.getSdfCompiledDefFile(module));
        final File sdf2ParenthesizeOutputFile = context.toFile(paths.getStrCompiledParenthesizerFile(module));
        final String sdf2ParenthesizeOutputModule = "include/" + module + "-parenthesize";

        final File sdf2RtgInputFile = context.toFile(paths.getSdfCompiledDefFile(module));
        final File sdf2RtgOutputFile = context.toFile(paths.getRtgFile(module));

        final File rtg2SigOutputPath = context.toFile(paths.getStrCompiledSigFile(module));

        @Nullable final File externalJar;
        @Nullable final File target;
        @Nullable final String externalJarFilename = config.externalJar();
        if(externalJarFilename != null) {
            externalJar = new File(externalJarFilename);
            try {
                target = context.toFile(paths.includeFolder().resolveFile(externalJar.getName()));
            } catch(FileSystemException e) {
                throw new RuntimeException("Unexpected exception.", e);
            }
        } else {
            externalJar = null;
            target = null;
        }

        final File strjInputFile = context.toFile(paths.strMainFile());
        final File strjOutputFile;
        final File strjDepFile;
        if(config.format() == StrategoFormat.ctree) {
            strjOutputFile = context.toFile(paths.strCompiledCtreeFile());
            strjDepFile = strjOutputFile;
        } else {
            strjOutputFile = context.toFile(paths.strJavaMainFile());
            strjDepFile = context.toFile(paths.strJavaTransFolder());
        }
        final File strjCacheDir = context.toFile(paths.cacheFolder());

        return new GenerateSourcesBuilder.Input(context, strategoMainFile, strategoJavaStrategiesMainFile, module,
            config.metaSdfName(), config.sdfArgs(), externalJar, target, strjInputFile, strjOutputFile, strjDepFile,
            strjCacheDir, config.strategoArgs(), config.format(), config.strategiesPackageName(),
            config.externalJarFlags(), rtg2SigOutputPath, sdf2RtgInputFile, sdf2RtgOutputFile,
            sdf2ParenthesizeInputFile, sdf2ParenthesizeOutputFile, sdf2ParenthesizeOutputModule, ppPackInputPath,
            ppPackOutputPath, ppGenInputPath, ppGenOutputPath, afGenOutputPath, makePermissiveOutputPath,
            sdf2tableOutputPath, externalDef, packSdfInputPath, packSdfOutputPath, packMetaSdfInputPath,
            packMetaSdfOutputPath, syntaxFolder, genSyntaxFolder);
    }

    private PackageBuilder.Input packageBuilderInput(SpoofaxLanguageSpecBuildInput input) throws FileSystemException {
        final ISpoofaxLanguageSpecConfig config = input.languageSpec.config();
        final ISpoofaxLanguageSpecPaths paths = input.languageSpec.paths();
        final SpoofaxContext context = new SpoofaxContext(input.languageSpec.location(), paths.buildFolder());

        final File strategoMainFile = context.toFile(paths.strMainFile());
        final File strategoJavaStrategiesMainFile = context.toFile(paths.strJavaStrategiesMainFile());

        final String module = config.sdfName();

        final File baseDir = context.toFile(paths.outputClassesFolder());

        @Nullable final File externalDef;
        if(config.externalDef() != null) {
            externalDef = context.toFile(context.resourceService().resolve(config.externalDef()));
        } else {
            externalDef = null;
        }
        final File packSdfInputPath = context.toFile(paths.getSdfMainFile(module));
        final File packSdfOutputPath = context.toFile(paths.getSdfCompiledDefFile(module));
        final File syntaxFolder = context.toFile(paths.syntaxFolder());
        final File genSyntaxFolder = context.toFile(paths.generatedSyntaxFolder());

        final File makePermissiveOutputPath = context.toFile(paths.getSdfCompiledPermissiveDefFile(module));
        final File sdf2tableOutputPath = context.toFile(paths.getSdfCompiledTableFile(module));

        final File ppGenInputPath = context.toFile(paths.getSdfCompiledDefFile(module));
        final File ppGenOutputPath = context.toFile(paths.getGeneratedPpCompiledFile(module));
        final File afGenOutputPath = context.toFile(paths.getGeneratedPpAfCompiledFile(module));

        final File ppPackInputPath = context.toFile(paths.getPpFile(module));
        final File ppPackOutputPath = context.toFile(paths.getPpAfCompiledFile(module));

        final File javaJarOutput = context.toFile(paths.strCompiledJavaJarFile());
        // TODO: get javajar-includes from project settings?
        // String[] paths = context.props.getOrElse("javajar-includes",
        // context.settings.packageStrategiesPath()).split("[\\s]+");
        final Collection<File> javaJarPaths =
            Lists.newArrayList(context.toFile(paths.strCompiledJavaStrategiesFolder()),
                context.toFile(paths.dsGeneratedInterpreterCompiledJavaFolder()),
                context.toFile(paths.dsManualInterpreterCompiledJavaFolder()));

        final FileObject target = paths.strCompiledJavaTransFolder();
        final File jarTarget = context.toFile(target);
        final File jarOutput = context.toFile(paths.strCompiledJarFile());

        final File targetPpAfFile = context.toFile(target.resolveFile(paths.getPpAfFilename(module)));
        final File targetGenPpAfFile = context.toFile(target.resolveFile(paths.getGeneratedPpAfFilename(module)));
        final File targetTblFile = context.toFile(target.resolveFile(paths.getSdfTableFilename(module)));

        return new PackageBuilder.Input(context, strategoMainFile, strategoJavaStrategiesMainFile, config.sdfArgs(),
            baseDir, config.format(), javaJarPaths, javaJarOutput, module, jarTarget, jarOutput, targetPpAfFile,
            targetGenPpAfFile, targetTblFile, ppPackInputPath, ppPackOutputPath, ppGenInputPath, ppGenOutputPath,
            afGenOutputPath, makePermissiveOutputPath, sdf2tableOutputPath, externalDef, packSdfInputPath,
            packSdfOutputPath, syntaxFolder, genSyntaxFolder);
    }
}
